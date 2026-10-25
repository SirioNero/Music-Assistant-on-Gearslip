package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.PcmDecoder
import it.xonos.ma.sendspin.SendspinClient
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Quanto passa da "play" (o "avanti") al primo suono, e dove se ne va il tempo: fino all'inizio
 * del flusso Sendspin (Music Assistant) e da lì al primo campione suonato (il telefono).
 * LEAD_MS e BUFFER_MS sono i margini che il telefono chiede al server (come in PlaybackService).
 */
fun main(): Unit = runBlocking {
    val url = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    val token = System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val lead = System.getenv("LEAD_MS")?.toInt() ?: 800
    val buffer = System.getenv("BUFFER_MS")?.toInt() ?: 300
    val repo = MaRepository(Settings())
    val library = Library(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    val outputs = mutableListOf<FakeOutput>()
    val engine = AudioEngine({ r, c -> FakeOutput(r, c).also { synchronized(outputs) { outputs += it } } }, { f -> PcmDecoder(f.channels, f.bitDepth) }, TimeFilter())
    val id = "xonos-latency"
    val identity = SendspinClient.Identity(id, "Prova ritardi", "JVM", "test", listOf(StreamFormat("pcm", 48000, 2, 16)), lead, buffer, 50, false)
    val client = SendspinClient(repo.http, engine, { identity }) { _, _ -> }
    client.start(url, token)
    withTimeout(20_000) { client.phase.first { it == SendspinClient.Phase.READY } }

    fun now() = System.nanoTime() / 1000
    /** Aspetta un'uscita nuova che suoni: (inizio flusso, primo suono) in ms da [t0]. */
    suspend fun measure(before: Int, t0: Long): Pair<Long, Long>? = withTimeoutOrNull(30_000) {
        while (true) {
            val fresh = synchronized(outputs) { outputs.drop(before) }
            val playing = fresh.firstOrNull { it.firstAudioAt > 0 }
            if (playing != null) return@withTimeoutOrNull (playing.createdAt - t0) / 1000 to (playing.firstAudioAt - t0) / 1000
            delay(5)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    // SOURCE=slow: file mandati alla velocità della musica dal finto server (come Spotify Soloist).
    val slow = System.getenv("SOURCE") == "slow"
    val tracks = if (slow) {
        (0..3).map { "http://127.0.0.1:8770/slow/$it.flac" }
    } else {
        val album = library.search("Emozioni").values.flatten().first { it.mediaType == "album" && it.name == "Emozioni" }
        library.albumTracks(album).map { it.uri }
    }
    println("Margini chiesti al server: anticipo $lead ms, buffer minimo $buffer ms; sorgente ${if (slow) "lenta" else "file"}")
    repeat(4) { round ->
        val before = synchronized(outputs) { outputs.size }
        val t0 = now()
        library.playMedia(id, listOf(tracks[round % tracks.size]))
        val sent = (now() - t0) / 1000
        val r = measure(before, t0)
        println("play ${round + 1}: comando ${sent} ms, flusso dopo ${r?.first} ms, primo suono dopo ${r?.second} ms")
        delay(3_000)
    }
    library.playMedia(id, tracks)
    delay(4_000)
    repeat(3) { round ->
        val before = synchronized(outputs) { outputs.size }
        val t0 = now()
        repo.command("player_queues/next") { put("queue_id", id) }
        val r = measure(before, t0)
        println("avanti ${round + 1}: flusso dopo ${r?.first} ms, primo suono dopo ${r?.second} ms")
        delay(3_000)
    }
    runCatching { repo.command("player_queues/stop") { put("queue_id", id) } }
    client.stop()
    repo.release()
    kotlin.system.exitProcess(0)
}
