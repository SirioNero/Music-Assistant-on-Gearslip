package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaItem
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import it.xonos.ma.data.objects
import it.xonos.ma.phone.model.HomePage
import it.xonos.ma.phone.model.ItemPage
import it.xonos.ma.phone.model.LibraryPage
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.Shelf
import it.xonos.ma.phone.model.Tab
import it.xonos.ma.phone.model.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * Quanto ci mette il modello del telefono e quanto tiene occupato il suo thread "principale":
 * collegamento, Home, prima pagina della libreria, una playlist lunga (PLAYLIST, per nome) e la
 * sola conversione JSON → MaItem. Sulla JVM il codice è più veloce che su un telefono: contano
 * i rapporti, e i blocchi del thread principale.
 */
fun main(): Unit = runBlocking {
    System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val playlistName = System.getenv("PLAYLIST") ?: "Playlist grande"
    val settings = Settings()
    val repo = MaRepository(settings)
    val library = Library(repo)

    val start = System.nanoTime()
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    println("Collegamento: ${ms(start)} ms")

    // Un solo thread per il modello, come il thread principale del telefono; un controllo ogni 16 ms
    // misura di quanto arriva in ritardo, cioè per quanto il thread è rimasto occupato.
    val main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + main)
    val stalls = mutableListOf<Long>()
    scope.launch {
        while (true) {
            val before = System.nanoTime()
            delay(FRAME_MS)
            val late = (System.nanoTime() - before) / 1_000_000 - FRAME_MS
            if (late > FRAME_MS) synchronized(stalls) { stalls += late }
        }
    }

    suspend fun phase(label: String, done: () -> Boolean) {
        synchronized(stalls) { stalls.clear() }
        val t = System.nanoTime()
        withTimeout(120_000) { while (!done()) delay(5) }
        val blocked = synchronized(stalls) { stalls.toList() }
        println("$label: ${ms(t)} ms · thread principale fermo oltre un fotogramma ${blocked.size} volte, al massimo ${blocked.maxOrNull() ?: 0} ms")
    }

    val model = PhoneModel(repo, library, settings, { "xonos-perf" }, MutableStateFlow(true), scope)

    val home = model.page as HomePage
    scope.launch { model.start(home) }
    phase("Home: ascolti recenti") { home.recent !is Load.Loading }
    phase("Home: righe di Per te") { home.rows.value?.all { it.items !is Load.Loading } == true }

    scope.launch { model.select(Tab.LIBRARY) }
    delay(50)
    val lib = model.page as LibraryPage
    scope.launch { model.start(lib) }
    phase("Libreria: voci") { lib.shelves != null }
    val albums = model.shelf(lib, Shelf.ALBUMS)
    scope.launch { model.loadMore(albums) }
    phase("Libreria: prima pagina di album") { !albums.loading && albums.items.isNotEmpty() }

    val playlist = library.libraryPage("playlists", 0, search = playlistName).firstOrNull { it.name == playlistName }
    if (playlist == null) {
        println("Nessuna playlist \"$playlistName\": la parte sulla playlist lunga si salta")
    } else {
        val page = ItemPage(playlist)
        scope.launch { model.push(page); model.start(page) }
        phase("Playlist \"${playlist.name}\" aperta") { page.tracks !is Load.Loading }
        println("   ${page.tracks.value?.size} brani")

        // La sola conversione, come fa Library.items: prima a freddo, poi a caldo.
        val raw = repo.command("music/playlists/playlist_tracks", 90_000) {
            put("item_id", playlist.itemId)
            put("provider_instance_id_or_domain", playlist.provider)
        } as JSONArray
        println("   JSON: ${raw.toString().length / 1024} KB")
        // Quanta memoria tiene l'albero JSON intero, rispetto ai soli MaItem che ne escono.
        val tree = heapUsed()
        val converted = raw.objects().mapNotNull(MaItem::from)
        val withItems = heapUsed()
        println("   memoria: albero JSON + MaItem ${(withItems) / 1_048_576} MB in tutto")
        val onlyItems = converted.also { System.gc() }
        println("   memoria dei soli MaItem: circa ${(withItems - tree) / 1_048_576} MB (${onlyItems.size} elementi)")
        repeat(6) { i ->
            val t = System.nanoTime()
            val items = raw.objects().mapNotNull(MaItem::from)
            println("   conversione ${i + 1}: ${items.size} elementi in ${ms(t)} ms")
        }
    }
    repo.release()
    main.close()
    System.exit(0)
}

private const val FRAME_MS = 16L

/** La memoria occupata adesso, dopo una pulizia (una stima: la JVM non è ART). */
private fun heapUsed(): Long {
    repeat(3) { System.gc(); Thread.sleep(100) }
    val r = Runtime.getRuntime()
    return r.totalMemory() - r.freeMemory()
}

private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000
