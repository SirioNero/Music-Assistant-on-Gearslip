package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Settings
import it.xonos.ma.playback.Menu
import it.xonos.ma.playback.MenuNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Percorre il menu come farebbe Gearslip: cartelle, iniziali, artisti, podcast e ricerche. */
fun main(): Unit = runBlocking {
    val repo = MaRepository(Settings())
    val library = Library(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    val menu = Menu(library, XmlLabels(System.getenv("LANG_XONOS") ?: "en")) { System.getenv("PLAYER_ID") ?: "autoradio-harness1" }

    fun line(n: MenuNode) = "${n.title} | ${n.subtitle ?: ""} | ${n.id}${if (n.browsable) " [apri]" else ""}${if (n.playable) " [suona]" else ""}"
    suspend fun show(id: String, max: Int = 6, indent: String = ""): List<MenuNode> {
        val t0 = System.nanoTime()
        val kids = menu.children(id)
        println("$indent[$id] ${kids.size} voci in ${(System.nanoTime() - t0) / 1_000_000} ms")
        kids.take(max).forEach { println("$indent   - ${line(it)}") }
        if (kids.size > max) println("$indent   ... ultima: ${line(kids.last())}")
        return kids
    }

    val root = show(Menu.ROOT, 20)
    for (category in root) {
        val kids = show(category.id, 4, "  ")
        // Le iniziali: si apre la prima e una a metà.
        if (kids.firstOrNull()?.id?.startsWith("abc|") == true) {
            show(kids.first().id, 3, "    ")
            kids.getOrNull(kids.size / 2)?.let { show(it.id, 3, "    ") }
        }
    }
    // Un artista con tutto: brani più ascoltati, album, tutti i brani.
    val artists = menu.children("cat|artists")
    val v = artists.firstOrNull { it.title == "V" }?.let { menu.children(it.id) } ?: artists
    v.firstOrNull { it.title.startsWith("Vasco") }?.let { vasco ->
        val page = show(vasco.id, 10, "  ")
        page.firstOrNull { it.id.startsWith("tracks|") }?.let { show(it.id, 3, "    ") }
        page.firstOrNull { it.id.startsWith("open|") }?.let { show(it.id, 3, "    ") }
    }
    // Un podcast della libreria.
    menu.children("cat|podcasts").firstOrNull()?.let { show(it.id, 3, "  ") }

    val queries = (System.getenv("QUERIES") ?: "morning,muschio,vasco,canzone,podcast elisir,elisir podcast,storie,album 2 di vasco,artista,xyz123").split(',')
    for (q in queries) {
        val t0 = System.nanoTime()
        val results = menu.search(q)
        println("\nRICERCA \"$q\": ${results.size} voci in ${(System.nanoTime() - t0) / 1_000_000} ms")
        results.forEach { println("   - ${line(it)}") }
        results.filter { it.id.startsWith("find|") }.forEach { folder ->
            val kids = menu.children(folder.id)
            println("     > ${folder.title}: ${kids.size} voci, prima: ${kids.firstOrNull()?.let(::line)}")
        }
    }
    repo.release()
    kotlin.system.exitProcess(0)
}
