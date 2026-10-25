package harness

import it.xonos.ma.data.ArtCache
import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Le copertine per l'auto, la notifica e il lettore (ArtCache). Al cambio di brano la stessa
 * copertina la chiedono insieme la notifica, il mini-lettore (copertina e colore di fondo) e
 * l'auto: deve arrivare a tutti. Per confronto si prova anche il modo di prima (un solo file
 * temporaneo per copertina: chi perde la gara restava senza immagine).
 */
fun main(): Unit = runBlocking {
    System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val repo = MaRepository(Settings())
    val library = Library(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    val album = library.search("emozioni")["albums"]?.firstOrNull { it.image != null } ?: error("nessun album con copertina")
    val base = repo.baseUrl ?: error("nessun indirizzo")
    val dir = Files.createTempDirectory("xonos-art").toFile()
    val cache = ArtCache(dir, repo.http)
    var failures = 0
    var before = 0
    repeat(ROUNDS) { round ->
        // Un file nuovo a ogni giro, come per un brano mai visto (Music Assistant accetta solo queste misure).
        val size = SIZES[round % SIZES.size]
        val url = MaRepository.imageUrl(base, album.image!!, size)
        val old = together(ASKERS) { asBefore(dir, "old-$round-$size.img", url, repo.http) }
        val now = together(ASKERS) { cache.get("new-$round-$size.img") { url } }
        val oldMissing = old.count { it == null || !jpeg(it) }
        val newMissing = now.count { it == null || !jpeg(it) }
        before += oldMissing
        failures += newMissing
        println("giro ${round + 1} (misura $size): senza copertina $oldMissing su $ASKERS come prima, $newMissing su $ASKERS ora")
    }
    val parts = dir.listFiles { f -> f.name.startsWith("new-") && f.name.endsWith(".part") }?.size ?: 0
    println("${if (failures == 0) "OK " else "NO "} La stessa copertina chiesta da $ASKERS parti insieme arriva a tutte · prima ne mancavano $before")
    println("${if (parts == 0) "OK " else "NO "} Nessun pezzo di download rimasto · $parts")
    println(if (failures == 0 && parts == 0) "Tutto bene" else "Qualcosa non va")
    repo.release()
    System.exit(0)
}

/** Fa [block] su [n] thread partiti nello stesso momento; i risultati in ordine. */
private fun <T> together(n: Int, block: () -> T): List<T?> {
    val start = CountDownLatch(1)
    val results = arrayOfNulls<Any>(n)
    val threads = (0 until n).map { i -> thread { start.await(); results[i] = block() } }
    start.countDown()
    threads.forEach { it.join() }
    @Suppress("UNCHECKED_CAST")
    return results.toList() as List<T?>
}

/** Un JPEG intero: comincia con FFD8 e finisce con FFD9. */
private fun jpeg(file: File): Boolean {
    val bytes = file.readBytes()
    return bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
        bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()
}

/** Il download delle copertine com'era prima (playback/Artwork fino alla 0.1.37). */
private fun asBefore(dir: File, name: String, url: String, http: OkHttpClient): File? {
    val file = File(dir, name)
    if (file.length() > 0) return file
    val client = http.newBuilder().readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    return try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val temp = File(file.parentFile, file.name + ".part")
            temp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!temp.renameTo(file)) return null
            file
        }
    } catch (e: Exception) {
        null
    }
}

private val SIZES = listOf(80, 160, 256, 512, 1024)
private const val ROUNDS = 10
private const val ASKERS = 4
