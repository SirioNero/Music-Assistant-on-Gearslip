package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import it.xonos.ma.sendspin.AudioDecoder
import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.SendspinClient
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.DataOutputStream
import java.io.File

/** Salva i pacchetti audio così come li riceve il motore audio dell'app: [tempo int64][lunghezza int32][dati]. */
fun main(): Unit = runBlocking {
    val url = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    val token = System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val codec = System.getenv("CODEC") ?: "opus"
    val file = File(System.getenv("OUT") ?: error("OUT"))
    val playerId = "autoradio-capture-$codec"
    val repo = MaRepository(Settings())
    val library = Library(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    val filter = TimeFilter()
    val sink = DataOutputStream(file.outputStream().buffered())
    var packets = 0
    val engine = AudioEngine({ _, _ -> null }, { f ->
        println("formato ${f.codec} ${f.sampleRate} header=${f.header}")
        object : AudioDecoder {
            override fun decode(data: ByteArray, offset: Int, length: Int, timestampUs: Long, out: (ShortArray, Int, Long) -> Unit) {
                synchronized(sink) {
                    sink.writeLong(timestampUs)
                    sink.writeInt(length)
                    sink.write(data, offset, length)
                }
                packets++
            }
            override fun release() = Unit
        }
    }, filter)
    val identity = SendspinClient.Identity(
        clientId = playerId, name = "Cattura $codec", manufacturer = "JVM", softwareVersion = "test",
        formats = listOf(StreamFormat(codec, 48000, 2, 16)), leadTimeMs = 800, minBufferMs = 300, volume = 80, muted = false,
    )
    val client = SendspinClient(repo.http, engine, { identity }) { _, _ -> }
    client.start(url, token)
    withTimeout(20_000) { client.phase.first { it == SendspinClient.Phase.READY } }
    delay(1_000)
    library.playMedia(playerId, listOf("library://album/1"))
    delay(System.getenv("PLAY_MS")?.toLong() ?: 50_000L)
    repo.command("player_queues/stop") { put("queue_id", playerId) }
    delay(500)
    client.stop()
    engine.release()
    synchronized(sink) { sink.close() }
    println("pacchetti salvati: $packets in ${file.length()} byte")
    repo.release()
    kotlin.system.exitProcess(0)
}
