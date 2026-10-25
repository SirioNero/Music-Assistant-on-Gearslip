package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaItem
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.PlaylistRemover
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.FixedFinder
import it.xonos.ma.phone.model.ItemPage
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.Notice
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.Tab
import it.xonos.ma.phone.model.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Le playlist modificabili. Con Library: una playlist nuova di Music Assistant, un brano e un album
 * aggiunti (l'album diventa i suoi brani, un doppione si salta), un brano tolto per posizione; poi
 * PlaylistRemover con tocchi veloci (deve togliere proprio quelli). Con il PhoneModel: "Aggiungi a
 * una playlist" in una esistente e in una nuova, "Togli" dalla playlist aperta, "Elimina".
 * Alla fine le playlist di prova si cancellano.
 */
fun main(): Unit = runBlocking {
    System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val settings = Settings()
    val repo = MaRepository(settings)
    val library = Library(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }

    /** I brani della playlist quando sono [count] (Music Assistant aggiunge e toglie in disparte, poco dopo). */
    suspend fun tracks(playlist: MaItem, count: Int): List<MaItem> {
        var last = emptyList<MaItem>()
        withTimeoutOrNull(15_000) {
            while (true) {
                last = library.playlistTracks(playlist, fresh = true)
                if (last.size == count) break
                delay(300)
            }
        }
        return last
    }

    // Quelle rimaste da un giro di prova interrotto.
    library.editablePlaylists().filter { it.name.startsWith("Prova Xonos") }.forEach { library.removeFromLibrary(it) }

    // ---------- Library ----------
    val stamp = System.currentTimeMillis() % 100_000
    val playlist = library.createPlaylist("Prova Xonos $stamp")
    check("Playlist nuova", playlist.provider == "library" && playlist.editable, "${playlist.uri} ${playlist.mappings}")
    check("Tra quelle modificabili", library.editablePlaylists().any { it.itemId == playlist.itemId })
    val brava = library.search("brava")["tracks"]?.first() ?: error("nessun brano")
    val emozioni = library.search("emozioni")["albums"]?.first { it.name == "Emozioni" } ?: error("nessun album")
    val bollicine = library.search("bollicine")["albums"]?.first { it.name == "Bollicine" } ?: error("nessun album")
    library.addToPlaylist(playlist, listOf(brava.uri))
    var list = tracks(playlist, 1)
    check("Un brano aggiunto", list.map { it.name } == listOf(brava.name), list.joinToString { "${it.position} ${it.name}" })
    // Subito dopo: Music Assistant lavora ancora alla prima aggiunta, e la sua cache resta vecchia.
    library.addToPlaylist(playlist, listOf(emozioni.uri, brava.uri, bollicine.uri))
    list = tracks(playlist, 9)
    check("Gli album diventano i loro brani, il doppione si salta", list.size == 9, list.joinToString { "${it.position} ${it.name}" })
    check("Posizioni da 1", list.map { it.position } == (1..list.size).toList())
    val third = list[2]
    library.removeFromPlaylist(playlist, listOf(third.position))
    list = tracks(playlist, 8)
    check("Brano tolto per posizione", list.size == 8 && list.none { it.uri == third.uri })

    // ---------- PlaylistRemover: tocchi veloci ----------
    val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + main)
    val rounds = mutableListOf<Pair<List<String>, Result<List<MaItem>>>>()
    val remover = PlaylistRemover(scope, library, playlist, { removed, result -> synchronized(rounds) { rounds += removed to result } })
    // Tre tocchi in fretta (un giro solo), poi altri due mentre il primo giro è ancora in corso.
    val first = listOf(list[1], list[4], list[6])
    val second = listOf(list[0], list[7])
    first.forEach { remover.remove(it.uri); delay(150) }
    delay(900)
    second.forEach { remover.remove(it.uri); delay(100) }
    withTimeoutOrNull(30_000) { while (synchronized(rounds) { rounds.sumOf { it.first.size } } < 5) delay(100) }
    val gone = (first + second).map { it.uri }.toSet()
    list = library.playlistTracks(playlist, fresh = true)
    val expected = tracks(playlist, 3).map { it.uri }
    check(
        "Tocchi veloci: tolti proprio quei cinque brani",
        expected.size == 3 && expected.none { it in gone } && rounds.all { it.second.isSuccess },
        "${rounds.size} giri; resta ${list.joinToString { it.name }}",
    )
    library.removeFromLibrary(playlist)

    // ---------- PhoneModel ----------
    val model = PhoneModel(repo, library, settings, ScreenCache(null), FixedFinder(), "Xonos (prova)", { "xonos-playlist" }, MutableStateFlow(true), scope)
    suspend fun <T> onMain(block: () -> T): T = withContext(main) { block() }
    suspend fun waitFor(what: () -> Boolean) = withTimeoutOrNull(20_000) { while (!onMain(what)) delay(50) } != null

    // Una playlist nuova dal telefono, con dentro il brano scelto.
    onMain { model.addToPlaylist(brava.uri) }
    val picker = onMain { model.playlistPicker } ?: error("nessuna scelta")
    check("La scelta si apre con le playlist modificabili", waitFor { picker.playlists is Load.Ready })
    val name = "Prova Xonos telefono $stamp"
    onMain { model.createPlaylist(picker, name) }
    check("Playlist nuova dal telefono", waitFor { model.playlistPicker == null } && onMain { model.notice } == Notice.AddedTo(name), "${onMain { model.notice }}")
    val created = library.editablePlaylists().firstOrNull { it.name == name }
    // Nelle playlist di Music Assistant i brani hanno l'indirizzo del loro servizio, non quello della libreria.
    check("C'è, con il brano dentro", created != null && tracks(created, 1).map { it.name } == listOf(brava.name))
    if (created != null) {
        // Un album in una playlist esistente.
        onMain { model.addToPlaylist(emozioni.uri) }
        val again = onMain { model.playlistPicker } ?: error("nessuna scelta")
        waitFor { again.playlists is Load.Ready }
        val target = again.playlists.value?.firstOrNull { it.itemId == created.itemId }
        check("La playlist nuova è tra quelle della scelta", target != null)
        if (target != null) onMain { model.addTo(again, target) }
        check("Album aggiunto", tracks(created, 5).size == 5 && onMain { model.notice } == Notice.AddedTo(name))

        // La playlist aperta: due brani tolti di fila, subito spariti dall'elenco, poi anche in Music Assistant.
        onMain { model.select(Tab.LIBRARY); model.open(created) }
        val page = onMain { model.page } as ItemPage
        onMain { model.start(page) }
        check("Playlist aperta, senza la cache vecchia", waitFor { page.tracks.value?.size == 5 }, "${onMain { page.tracks.value?.size }}")
        val shown = onMain { page.tracks.value.orEmpty() }
        check("Si può modificare e cancellare", onMain { model.canEdit(page.item) && model.canDelete(page.item) })
        onMain { model.removeFromPlaylist(page, shown[1]) }
        onMain { model.removeFromPlaylist(page, page.tracks.value!![2]) }
        val left = onMain { page.tracks.value.orEmpty() }
        check("Tolti subito dall'elenco", left.size == 3 && left.none { it.uri == shown[1].uri || it.uri == shown[3].uri }, left.joinToString { it.name })
        val server = tracks(created, 3)
        check("E poi in Music Assistant", server.map { it.uri } == left.map { it.uri }, server.joinToString { it.name })

        // Elimina.
        onMain { model.deletePlaylist(page) }
        check("Playlist eliminata", waitFor { model.notice == Notice.PlaylistDeleted } && library.editablePlaylists().none { it.itemId == created.itemId })
        check("E la schermata si chiude", onMain { model.page !== page })
    }
    library.editablePlaylists().filter { it.name.startsWith("Prova Xonos") }.forEach { library.removeFromLibrary(it) }
    println(if (failures == 0) "Tutto bene" else "Qualcosa non va: $failures")
    repo.release()
    main.close()
    System.exit(0)
}
