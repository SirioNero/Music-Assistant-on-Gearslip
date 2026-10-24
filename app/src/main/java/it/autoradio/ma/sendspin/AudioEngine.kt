package it.autoradio.ma.sendspin

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Il formato di uno stream, come lo annuncia il server in stream/start. */
data class StreamFormat(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    /** L'intestazione del codec in Base64 (per FLAC), se c'è. */
    val header: String? = null,
) {
    fun headerBytes(): ByteArray? = header?.let { Base64.getMimeDecoder().decode(it) }

    fun toJson(): JSONObject = JSONObject()
        .put("codec", codec)
        .put("channels", channels)
        .put("sample_rate", sampleRate)
        .put("bit_depth", bitDepth)

    companion object {
        fun from(o: JSONObject): StreamFormat? {
            val codec = o.optString("codec").ifEmpty { return null }
            val rate = o.optInt("sample_rate")
            val channels = o.optInt("channels")
            if (rate <= 0 || channels <= 0) return null
            return StreamFormat(
                codec = codec,
                sampleRate = rate,
                channels = channels,
                bitDepth = o.optInt("bit_depth", 16),
                header = if (o.isNull("codec_header")) null else o.optString("codec_header").ifEmpty { null },
            )
        }
    }
}

/** Dove finisce il PCM a 16 bit interleaved: sul telefono un AudioTrack. */
interface PcmOutput {
    val sampleRate: Int
    val channels: Int

    /** Ritardo stimato tra la scrittura e l'ascolto, quando l'uscita non sa ancora dirlo meglio. */
    val latencyUs: Long

    fun play()

    /** Scrive [frames] fotogrammi partendo da [offsetFrames]; blocca finché c'è posto. Restituisce quanti ne ha scritti. */
    fun write(data: ShortArray, offsetFrames: Int, frames: Int): Int

    /** Quando si sentirà il fotogramma numero [frame] (contando dall'inizio), sul nostro orologio; null se non lo sa. */
    fun presentationTimeUs(frame: Long): Long?

    fun setGain(gain: Float)

    /** Smette subito di suonare e butta ciò che ha in coda; si può chiamare da qualsiasi thread. */
    fun interrupt()

    fun release()
}

/** Da pacchetti compressi a PCM a 16 bit interleaved. */
interface AudioDecoder {
    /** Decodifica un pacchetto; chiama [out] per ogni blocco di PCM prodotto, con il suo tempo del server. */
    fun decode(data: ByteArray, offset: Int, length: Int, timestampUs: Long, out: (ShortArray, Int, Long) -> Unit)

    fun release()
}

/** PCM così com'è: interi little-endian; quelli a 24 o 32 bit si riducono a 16. */
class PcmDecoder(private val channels: Int, private val bitDepth: Int) : AudioDecoder {
    override fun decode(data: ByteArray, offset: Int, length: Int, timestampUs: Long, out: (ShortArray, Int, Long) -> Unit) {
        val bytes = bitDepth / 8
        if (bytes !in 2..4) return
        val samples = length / bytes
        val frames = samples / channels
        if (frames == 0) return
        val pcm = ShortArray(frames * channels)
        if (bytes == 2) {
            ByteBuffer.wrap(data, offset, frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        } else {
            for (i in pcm.indices) {
                // Il byte più significativo e quello sotto: gli ultimi due del campione.
                val p = offset + i * bytes + bytes - 2
                pcm[i] = ((data[p + 1].toInt() shl 8) or (data[p].toInt() and 0xFF)).toShort()
            }
        }
        out(pcm, frames, timestampUs)
    }

    override fun release() = Unit
}

/**
 * Suona l'audio di Sendspin al momento giusto.
 *
 * I pacchetti arrivano dalla rete con il tempo del server in cui vanno ascoltati, anche mezzo minuto
 * prima. Un thread li decodifica e li scrive sull'uscita: se è in ritardo salta l'audio già passato,
 * se è in anticipo aspetta con un po' di silenzio, e i piccoli scarti (gli orologi non vanno mai
 * proprio alla stessa velocità) li recupera togliendo o ripetendo pochi campioni, senza che si senta.
 *
 * I metodi pubblici si chiamano dal thread della rete, in ordine di arrivo dei messaggi.
 */
class AudioEngine(
    private val outputs: (sampleRate: Int, channels: Int) -> PcmOutput?,
    private val decoders: (StreamFormat) -> AudioDecoder?,
    val filter: TimeFilter,
    private val clock: () -> Long = { System.nanoTime() / 1000 },
) {
    private sealed interface Event
    private class Start(val format: StreamFormat, val fresh: Boolean) : Event
    private class Chunk(val generation: Int, val timestampUs: Long, val data: ByteArray, val offset: Int, val length: Int) : Event
    private object Reset : Event
    private object Finish : Event

    private val lock = Object()
    private val events = ArrayDeque<Event>()
    private val outputLock = Any()

    @Volatile private var generation = 0
    @Volatile private var released = false
    @Volatile private var gain = 1f
    @Volatile private var duck = 1f
    private var thread: Thread? = null

    // Lo stato del protocollo, visto dal thread della rete.
    private var streamOpen = false
    private var freshNeeded = true
    private var lastChunkUs = Long.MIN_VALUE

    // Lo stato dell'uscita, visto dal thread audio.
    private var format: StreamFormat? = null
    private var decoder: AudioDecoder? = null
    @Volatile private var output: PcmOutput? = null
    private var framesWritten = 0L
    private var smoothedErrorUs = 0.0
    private var lastAudioAt = 0L
    private var silence = ShortArray(0)
    private var statSince = 0L
    private var statBlocks = 0
    private var statSum = 0L
    private var statMax = 0L

    private val _outputting = MutableStateFlow(false)

    /** Vero mentre esce davvero musica: falso in attesa del primo audio, in pausa o se la rete si è fermata. */
    val outputting: StateFlow<Boolean> = _outputting.asStateFlow()

    /** Fin dove arriva l'audio ricevuto, sull'orologio del server; utile per riprendere dopo un buco di rete. */
    val bufferedUntilServerUs: Long get() = lastChunkUs

    /** Inizia uno stream, o ne cambia il formato senza interromperlo. */
    fun startStream(format: StreamFormat) {
        val fresh = !streamOpen || freshNeeded
        if (fresh) interrupt()
        streamOpen = true
        freshNeeded = false
        post(Start(format, fresh))
    }

    /** Un pacchetto audio: [timestampUs] è il tempo del server in cui va ascoltato il primo campione. */
    fun audio(timestampUs: Long, data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (!streamOpen || freshNeeded || length <= 0) return
        lastChunkUs = timestampUs
        post(Chunk(generation, timestampUs, data, offset, length))
    }

    /** Il server ha saltato altrove (ricerca, brano diverso): si butta l'audio in coda e si continua. */
    fun clear() {
        if (!streamOpen) return
        interrupt()
        post(Reset)
    }

    /** Il server ha finito: si smette subito di suonare. */
    fun end() {
        streamOpen = false
        interrupt()
        post(Finish)
    }

    /**
     * La connessione è caduta: l'audio già ricevuto continua a suonare finché basta,
     * ma il prossimo stream ripartirà da capo.
     */
    fun connectionLost() {
        freshNeeded = true
    }

    /** Volume (0-100) e muto come li chiede Music Assistant; il volume percepito vuole la potenza 1,5. */
    fun setVolume(volume: Int, muted: Boolean) {
        gain = if (muted) 0f else (volume.coerceIn(0, 100) / 100f).pow(1.5f)
        applyGain()
    }

    /** Abbassa (o azzera) il volume per un momento, per esempio durante una telefonata. */
    fun setDuck(factor: Float) {
        duck = factor.coerceIn(0f, 1f)
        applyGain()
    }

    fun release() {
        released = true
        interrupt()
        synchronized(lock) { lock.notifyAll() }
        thread?.join(1_000)
        thread = null
        closeStream()
    }

    private fun applyGain() {
        synchronized(outputLock) { output?.setGain(gain * duck) }
    }

    private fun interrupt() {
        generation++
        lastChunkUs = Long.MIN_VALUE
        synchronized(outputLock) { output?.interrupt() }
        synchronized(lock) { events.removeAll { it is Chunk } }
    }

    private fun post(event: Event) {
        synchronized(lock) {
            if (released) return
            // Più di un minuto di audio in coda vuol dire che il thread audio è fermo: si butta il più vecchio.
            if (event is Chunk && events.size > MAX_QUEUED) events.removeFirstOrNull()
            events.addLast(event)
            lock.notifyAll()
        }
        if (thread == null) {
            thread = Thread(::run, "autoradio-audio").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    private fun run() {
        while (!released) {
            val event = synchronized(lock) {
                if (events.isEmpty()) lock.wait(IDLE_WAIT_MS)
                events.removeFirstOrNull()
            }
            try {
                when (event) {
                    null -> checkStarved()
                    is Start -> begin(event)
                    is Chunk -> if (event.generation == generation) decode(event)
                    Reset -> restartOutput()
                    Finish -> closeStream()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Errore nell'uscita audio", e)
            }
        }
    }

    private fun begin(start: Start) {
        if (start.fresh) closeStream()
        val previous = format
        format = start.format
        if (start.fresh || previous == null || previous.codec != start.format.codec || previous.header != start.format.header) {
            decoder?.release()
            decoder = decoders(start.format)
            if (decoder == null) Log.w(TAG, "Formato non supportato: ${start.format}")
        }
        if (previous != null && (previous.sampleRate != start.format.sampleRate || previous.channels != start.format.channels)) {
            // Nuova frequenza: serve un'uscita nuova (succede solo se il server ignora il formato chiesto).
            releaseOutput()
        }
        Log.i(TAG, "Stream ${start.format.codec} ${start.format.sampleRate} Hz, ${start.format.channels} canali")
    }

    private fun restartOutput() {
        releaseOutput()
        format?.let {
            decoder?.release()
            decoder = decoders(it)
        }
        _outputting.value = false
    }

    private fun closeStream() {
        releaseOutput()
        decoder?.release()
        decoder = null
        format = null
        _outputting.value = false
    }

    private fun releaseOutput() {
        synchronized(outputLock) {
            output?.release()
            output = null
        }
        framesWritten = 0
        smoothedErrorUs = 0.0
    }

    private fun checkStarved() {
        if (_outputting.value && clock() - lastAudioAt > STARVED_US) _outputting.value = false
    }

    private fun decode(chunk: Chunk) {
        val decoder = decoder ?: return
        decoder.decode(chunk.data, chunk.offset, chunk.length, chunk.timestampUs) { pcm, frames, timestampUs ->
            render(pcm, frames, timestampUs, chunk.generation)
        }
    }

    private fun ensureOutput(format: StreamFormat): PcmOutput? {
        output?.let { return it }
        val created = outputs(format.sampleRate, format.channels) ?: return null
        synchronized(outputLock) {
            output = created
            created.setGain(gain * duck)
        }
        framesWritten = 0
        smoothedErrorUs = 0.0
        created.play()
        return created
    }

    private fun render(pcm: ShortArray, frames: Int, timestampUs: Long, gen: Int) {
        if (gen != generation || frames <= 0) return
        val format = format ?: return
        val out = ensureOutput(format) ?: return
        val fresh = framesWritten == 0L
        if (fresh && !prime(out, gen)) return

        val target = filter.toClient(timestampUs)
        val error = heardAt(out) - target // positivo: siamo in ritardo
        report(error, fresh)
        var start = 0
        var count = frames
        if (fresh || abs(error) > HARD_US) {
            // All'avvio, o dopo un buco della rete: ci si allinea in un colpo solo.
            smoothedErrorUs = 0.0
            if (error > 0) {
                // Audio già passato: lo si salta.
                val drop = framesFor(error, out.sampleRate).coerceAtMost(count.toLong()).toInt()
                start = drop
                count -= drop
                if (count == 0) return
            } else if (error < 0 && !waitFor(out, target, gen)) {
                return
            }
        } else {
            smoothedErrorUs = smoothedErrorUs * 0.9 + error * 0.1
            if (abs(smoothedErrorUs) > SOFT_US && count > 200) {
                // Un passo piccolo, mezzo per cento al massimo: non si sente.
                val step = max(1, count / 200)
                if (smoothedErrorUs > 0) {
                    start = step
                    count -= step
                } else {
                    val stretched = ShortArray((count + step) * format.channels)
                    System.arraycopy(pcm, 0, stretched, step * format.channels, count * format.channels)
                    for (i in 0 until step) System.arraycopy(pcm, 0, stretched, i * format.channels, format.channels)
                    if (writeFrames(out, stretched, 0, count + step, gen)) markAudio()
                    return
                }
            }
        }
        if (writeFrames(out, pcm, start, count, gen)) markAudio()
    }

    /** Ogni tanto nei log: quanto è lontano l'ascolto dal momento voluto dal server. */
    private fun report(error: Long, fresh: Boolean) {
        val now = clock()
        if (fresh) Log.d(TAG, "Avvio a ${error / 1000} ms dal momento giusto")
        statBlocks++
        statSum += abs(error)
        statMax = max(statMax, abs(error))
        if (now - statSince < STATS_US) return
        if (statBlocks > 0) {
            Log.i(TAG, "Audio: scarto medio ${statSum / statBlocks / 1000} ms, massimo ${statMax / 1000} ms su $statBlocks blocchi")
        }
        statSince = now
        statBlocks = 0
        statSum = 0
        statMax = 0
    }

    /** Apre l'uscita con un po' di silenzio, finché non sa dire quando suona davvero. */
    private fun prime(out: PcmOutput, gen: Int): Boolean {
        val step = framesFor(PRIME_STEP_US, out.sampleRate).toInt()
        val limit = framesFor(PRIME_MAX_US, out.sampleRate)
        var primed = 0L
        while (out.presentationTimeUs(framesWritten) == null && primed < limit) {
            if (!writeSilence(out, step, gen)) return false
            primed += step
        }
        return true
    }

    /** Quando si sentirà il prossimo campione scritto; mai prima di adesso, anche se l'uscita è rimasta a secco. */
    private fun heardAt(out: PcmOutput): Long {
        val now = clock()
        val estimate = out.presentationTimeUs(framesWritten) ?: (now + out.latencyUs)
        return max(estimate, now + MIN_LATENCY_US)
    }

    /** Silenzio fino a [target]: se manca molto si aspetta prima, per non riempirsi di silenzio. */
    private fun waitFor(out: PcmOutput, target: Long, gen: Int): Boolean {
        var wait = target - heardAt(out)
        while (wait > MAX_SILENCE_US) {
            Thread.sleep(SLEEP_STEP_MS)
            if (gen != generation || released) return false
            wait = target - heardAt(out)
        }
        return wait <= 0 || writeSilence(out, framesFor(wait, out.sampleRate).toInt(), gen)
    }

    private fun markAudio() {
        lastAudioAt = clock()
        if (!_outputting.value) _outputting.value = true
    }

    private fun writeSilence(out: PcmOutput, frames: Int, gen: Int): Boolean {
        val needed = min(frames, framesFor(MAX_SILENCE_US, out.sampleRate).toInt()) * out.channels
        if (silence.size < needed) silence = ShortArray(needed)
        return writeFrames(out, silence, 0, needed / out.channels, gen)
    }

    /** Scrive a pezzi, così uno stop arriva in fretta anche mentre l'uscita è piena. */
    private fun writeFrames(out: PcmOutput, data: ShortArray, offset: Int, frames: Int, gen: Int): Boolean {
        var done = 0
        val piece = max(1, out.sampleRate / 50)
        while (done < frames) {
            if (gen != generation || released) return false
            val n = out.write(data, offset + done, min(piece, frames - done))
            if (n <= 0) return false
            done += n
            framesWritten += n
        }
        return true
    }

    private fun framesFor(us: Long, rate: Int): Long = us * rate / 1_000_000

    private companion object {
        const val TAG = "Autoradio"

        /** Oltre questo scarto si salta o si aspetta; sotto si corregge piano. */
        const val HARD_US = 400_000L
        const val SOFT_US = 30_000.0
        const val MIN_LATENCY_US = 20_000L
        const val STATS_US = 30_000_000L
        const val PRIME_STEP_US = 20_000L
        const val PRIME_MAX_US = 600_000L
        const val MAX_SILENCE_US = 3_000_000L
        const val STARVED_US = 1_500_000L
        const val SLEEP_STEP_MS = 50L
        const val IDLE_WAIT_MS = 250L
        const val MAX_QUEUED = 4_000
    }
}
