package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.FixedFinder
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.SearchPage
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
import java.util.concurrent.Executors

/**
 * La ricerca del telefono: quando compaiono i primi risultati (la libreria) e quando arrivano
 * quelli dei servizi. QUERY, separate da virgole. Music Assistant tiene in memoria le ricerche
 * per un po': per vedere la differenza serve una parola nuova e un servizio lento.
 */
fun main(): Unit = runBlocking {
    System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val queries = (System.getenv("QUERY") ?: "acqua azzurra").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val settings = Settings()
    val repo = MaRepository(settings)
    val library = Library(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    val main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()
    val model = PhoneModel(repo, library, settings, ScreenCache(null), FixedFinder(), "Xonos (prova)", { "xonos-search" }, MutableStateFlow(true), CoroutineScope(SupervisorJob() + main))
    withContext(main) { model.select(Tab.SEARCH) }
    val page = model.page as SearchPage
    for (query in queries) {
        val start = System.nanoTime()
        withContext(main) { model.search(page, query) }
        var first: Long? = null
        var firstCount = 0
        withTimeout(60_000) {
            while (true) {
                val ready = (page.results as? Load.Ready)?.value?.takeIf { it.query == query }
                if (ready != null && first == null) {
                    first = ms(start)
                    firstCount = ready.byType.values.sumOf { it.size }
                }
                if (ready != null && !page.searching) break
                delay(5)
            }
        }
        val all = page.results?.value
        println(
            "\"$query\": primi risultati dopo $first ms ($firstCount), tutti dopo ${ms(start)} ms " +
                "(${all?.byType?.mapValues { it.value.size }}), il migliore: ${all?.best?.name} (${all?.best?.mediaType})",
        )
    }
    repo.release()
    main.close()
    System.exit(0)
}

private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000
