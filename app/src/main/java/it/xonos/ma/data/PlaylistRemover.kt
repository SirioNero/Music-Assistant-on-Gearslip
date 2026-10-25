package it.xonos.ma.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Toglie brani da una playlist della libreria. Music Assistant toglie per posizione e, dalla 2.10,
 * lo fa in disparte, poco dopo aver risposto: due richieste ravvicinate potrebbero togliere il
 * brano sbagliato. Qui i brani toccati in fretta partono insieme, con le posizioni lette un attimo
 * prima (senza la cache di Music Assistant), e il giro dopo parte solo quando i brani del primo
 * sono davvero spariti.
 */
class PlaylistRemover(
    private val scope: CoroutineScope,
    private val library: Library,
    private val playlist: MaItem,
    /** A ogni giro finito: i brani tolti e l'elenco vero della playlist, o l'errore. */
    private val done: (removed: List<String>, result: Result<List<MaItem>>) -> Unit,
    private val gatherMs: Long = GATHER_MS,
) {
    private val lock = Any()
    private val pending = mutableListOf<String>()
    private var running = false

    /** Toglie il brano [uri] (se c'è più volte, la prima). */
    fun remove(uri: String) {
        synchronized(lock) {
            pending += uri
            if (running) return
            running = true
        }
        scope.launch { pump() }
    }

    private suspend fun pump() {
        while (true) {
            // Un attimo per raccogliere i tocchi vicini in un giro solo.
            delay(gatherMs)
            val batch = synchronized(lock) {
                pending.toList().also {
                    pending.clear()
                    if (it.isEmpty()) running = false
                }
            }
            if (batch.isEmpty()) return
            val result = try {
                Result.success(removeNow(batch))
            } catch (e: CancellationException) {
                synchronized(lock) { running = false }
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            done(batch, result)
        }
    }

    private suspend fun removeNow(uris: List<String>): List<MaItem> {
        val before = library.playlistTracks(playlist, fresh = true)
        val left = before.toMutableList()
        val positions = uris.mapNotNull { uri ->
            val track = left.firstOrNull { it.uri == uri } ?: return@mapNotNull null
            left.remove(track)
            track.position
        }
        if (positions.isEmpty()) return before
        library.removeFromPlaylist(playlist, positions)
        // Music Assistant li toglie poco dopo: si aspetta di vederlo.
        for (wait in WAITS_MS) {
            delay(wait)
            val now = library.playlistTracks(playlist, fresh = true)
            if (now.size <= before.size - positions.size) return now
        }
        throw MaException("La playlist non è cambiata")
    }

    companion object {
        const val GATHER_MS = 600L
        private val WAITS_MS = listOf(300L, 700L, 1_500L, 3_000L, 5_000L)
    }
}
