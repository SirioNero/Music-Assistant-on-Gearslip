package harness

import it.xonos.ma.data.MaClient
import it.xonos.ma.data.MaException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Ogni modo di sbagliare porta il suo problema: è quello che l'app traduce. */
fun main(): Unit = runBlocking {
    val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
    val token = System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val cases = listOf(
        "token sbagliato" to ("http://127.0.0.1:8095" to "abc"),
        "non è Music Assistant" to ("http://127.0.0.1:8770" to token),
        "porta chiusa" to ("http://127.0.0.1:9" to token),
        "indirizzo malformato" to ("http://exa mple:8095" to token),
        "nome sconosciuto" to ("http://nonesiste.invalid:8095" to token),
        "tutto giusto" to ("http://127.0.0.1:8095" to token),
    )
    for ((label, args) in cases) {
        val client = MaClient(http)
        try {
            val server = client.connect(args.first, args.second, 6_000)
            println("$label: collegato a ${server.version}")
        } catch (e: MaException) {
            println("$label: ${e.problem} detail=${e.detail} code=${e.code} (${e.message})")
        } finally {
            client.close()
        }
    }
    kotlin.system.exitProcess(0)
}
