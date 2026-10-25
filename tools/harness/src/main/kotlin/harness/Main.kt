package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.Settings
import it.xonos.ma.sendspin.AudioDecoder
import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.PcmDecoder
import it.xonos.ma.sendspin.PcmOutput
import it.xonos.ma.sendspin.SendspinClient
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Un'uscita finta che consuma in tempo reale, come un AudioTrack, e conta cosa riceve. */
class FakeOutput(override val sampleRate: Int, override val channels: Int) : PcmOutput {
    private val bufferFrames = sampleRate / 4
    override val latencyUs = 300_000L
    @Volatile private var t0 = -1L
    @Volatile private var written = 0L
    @Volatile var interrupted = false
    val audioFrames = AtomicLong()
    val silentFrames = AtomicLong()
    var firstAudioAt = -1L
    var createdAt = now()

    private fun now() = System.nanoTime() / 1000
    private fun consumed(): Long = if (t0 < 0) 0 else ((now() - t0) * sampleRate / 1_000_000).coerceIn(0, written)

    override fun play() = Unit

    override fun write(data: ShortArray, offsetFrames: Int, frames: Int): Int {
        if (interrupted) return 0
        synchronized(this) {
            if (t0 < 0) t0 = now() + 50_000
            val c = (now() - t0) * sampleRate / 1_000_000
            if (c > written) t0 = now() - written * 1_000_000 / sampleRate
        }
        while (written - consumed() > bufferFrames) {
            Thread.sleep(2)
            if (interrupted) return 0
        }
        var silent = true
        for (i in offsetFrames * channels until (offsetFrames + frames) * channels) if (data[i].toInt() != 0) { silent = false; break }
        if (silent) silentFrames.addAndGet(frames.toLong()) else {
            if (firstAudioAt < 0) firstAudioAt = now()
            audioFrames.addAndGet(frames.toLong())
        }
        written += frames
        return frames
    }

    override fun presentationTimeUs(frame: Long): Long? = if (t0 < 0) null else t0 + frame * 1_000_000 / sampleRate
    override fun setGain(gain: Float) { println("   [out] gain=$gain") }
    override fun interrupt() { interrupted = true }
    override fun release() { println("   [out] released: audio=${audioFrames.get()} silent=${silentFrames.get()} frames") }
}

class CountingDecoder(private val inner: AudioDecoder) : AudioDecoder {
    val decoded = AtomicLong()
    var chunks = 0
    var misaligned = 0
    var bytes = 0L
    var firstTs = -1L
    var lastTs = -1L
    var lastLen = 0
    var gapVsDur = 0L
    override fun decode(data: ByteArray, offset: Int, length: Int, timestampUs: Long, out: (ShortArray, Int, Long) -> Unit) {
        chunks++
        bytes += length
        if (length % 4 != 0) misaligned++
        if (firstTs < 0) firstTs = timestampUs
        if (lastTs >= 0) gapVsDur += (timestampUs - lastTs) - lastLen.toLong() / 4 * 1_000_000 / 48000
        lastTs = timestampUs
        lastLen = length
        if (chunks % 500 == 0) println("   [dec] chunks=$chunks misaligned=$misaligned len=$length bytes=$bytes spanTs=${timestampUs - firstTs}us audioUs=${bytes / 4 * 1_000_000 / 48000} gap-dur=$gapVsDur")
        inner.decode(data, offset, length, timestampUs) { pcm, frames, ts -> decoded.addAndGet(frames.toLong()); out(pcm, frames, ts) }
    }
    override fun release() = inner.release()
}

fun main(): Unit = runBlocking {
    val url = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    val token = System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val playerId = System.getenv("PLAYER_ID") ?: "autoradio-harness1"
    val repo = MaRepository(Settings())
    val library = Library(repo)
    repo.acquire()
    val connected = withTimeout(15_000) { repo.connection.first { it is Connection.Connected } } as Connection.Connected
    println("API: ${connected.server}")
    repo.addEventListener { event, objectId, data ->
        if (objectId == playerId && event == "queue_updated") {
            val q = QueueState.from(data as? JSONObject)
            println("   [ev] queue ${q?.state} idx=${q?.currentIndex} elapsed=${"%.1f".format(q?.elapsedSec ?: 0.0)} cur=${q?.current?.title}")
        }
    }

    val filter = TimeFilter()
    val outputs = mutableListOf<FakeOutput>()
    val decoders = mutableListOf<CountingDecoder>()
    val engine = AudioEngine(
        { rate, channels -> FakeOutput(rate, channels).also { outputs += it } },
        { f -> if (f.codec == "pcm") CountingDecoder(PcmDecoder(f.channels, f.bitDepth)).also { decoders += it } else null },
        filter,
    )
    val identity = SendspinClient.Identity(
        clientId = playerId, name = "Banco di prova", manufacturer = "JVM", softwareVersion = "test",
        formats = listOf(StreamFormat("pcm", 48000, 2, 16)), leadTimeMs = 800, minBufferMs = 300, volume = 80, muted = false,
    )
    val client = SendspinClient(repo.http, engine, { identity }) { v, m -> println("   [vol] volume=$v muted=$m") }
    client.start(url, token)
    withTimeout(20_000) { client.phase.first { it == SendspinClient.Phase.READY } }
    println("Sendspin READY: filter count=${filter.count} offset=${"%.0f".format(filter.offsetUs)}us")
    delay(1_500)

    suspend fun step(label: String, wait: Long, action: suspend () -> Unit) {
        println("== $label")
        action()
        delay(wait)
        val o = outputs.lastOrNull()
        println("   outputting=${engine.outputting.value} outputs=${outputs.size} lastOut audio=${o?.audioFrames?.get()} silent=${o?.silentFrames?.get()} decoded=${decoders.lastOrNull()?.decoded?.get()} filter=${filter.count}")
    }

    val probeAt = System.nanoTime() / 1000 + 60_000_000
    val watcher = launch {
        while (true) {
            delay(5_000)
            println("   [filter] count=${filter.count} offset=${"%.0f".format(filter.offsetUs)} toClient(fixed)=${filter.toClient(probeAt) - probeAt}")
        }
    }
    val start = System.nanoTime()
    val playMs = System.getenv("PLAY_MS")?.toLong() ?: 8_000L
    step("play album", playMs) { library.playMedia(playerId, listOf("library://album/1")) }
    val o = outputs.last()
    val playedSec = o.audioFrames.get() / 48000.0
    println("   first audio after ${(o.firstAudioAt - o.createdAt) / 1000} ms from output creation; played ${"%.2f".format(playedSec)} s in ${playMs / 1000} s window")
    step("seek 20s", 4_000) { repo.command("player_queues/seek") { put("queue_id", playerId); put("position", 20) } }
    step("next", 4_000) { repo.command("player_queues/next") { put("queue_id", playerId) } }
    step("volume 40", 1_000) { repo.command("players/cmd/volume_set") { put("player_id", playerId); put("volume_level", 40) } }
    step("pause", 2_000) { repo.command("player_queues/pause") { put("queue_id", playerId) } }
    step("play", 4_000) { repo.command("player_queues/play") { put("queue_id", playerId) } }
    step("stop", 2_000) { repo.command("player_queues/stop") { put("queue_id", playerId) } }
    println("elapsed ${(System.nanoTime() - start) / 1_000_000} ms")
    watcher.cancel()
    client.stop()
    engine.release()
    repo.release()
    kotlin.system.exitProcess(0)
}
