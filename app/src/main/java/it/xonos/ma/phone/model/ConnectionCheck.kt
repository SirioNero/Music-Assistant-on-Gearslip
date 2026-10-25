package it.xonos.ma.phone.model

import it.xonos.ma.data.MaClient
import it.xonos.ma.data.MaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * La prova della connessione dalle impostazioni: per un indirizzo, l'API (versione, quante
 * playlist) e l'audio (/sendspin accetta il token?). Lo schermo poi lo racconta a parole.
 */
class ConnectionCheck(private val repo: MaRepository) {

    enum class Place { HOME, AWAY }

    /** Come ha risposto /sendspin. */
    sealed interface Audio {
        data object Ready : Audio
        data object Unexpected : Audio
        data class Closed(val reason: String?) : Audio
        data object Denied : Audio
        data object TooOld : Audio
        data class Error(val message: String) : Audio
        data object Unreachable : Audio
        data object NoAnswer : Audio
    }

    sealed interface Result {
        val place: Place

        data class Ok(override val place: Place, val version: String, val playlists: Int?, val audio: Audio) : Result
        data class Failed(override val place: Place, val error: Throwable) : Result
    }

    suspend fun check(place: Place, url: String, token: String): Result {
        val client = repo.newClient()
        return try {
            val server = client.connect(url, token)
            val playlists = (client.command("music/playlists/count") as? Number)?.toInt()
            Result.Ok(place, server.version, playlists, audio(url, token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failed(place, e)
        } finally {
            client.close()
        }
    }

    /** Apre /sendspin come farà il lettore e controlla che il server accetti il token. */
    private suspend fun audio(url: String, token: String): Audio {
        val answer = CompletableDeferred<Audio>()
        val request = Request.Builder().url(MaClient.websocketUrl(url, "/sendspin")).build()
        val socket = repo.http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ok = runCatching { JSONObject(text).optString("type") == "auth_ok" }.getOrDefault(false)
                answer.complete(if (ok) Audio.Ready else Audio.Unexpected)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                answer.complete(Audio.Closed(reason.ifBlank { null }))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                answer.complete(
                    when {
                        response?.code == 404 -> Audio.TooOld
                        response?.code == 401 || response?.code == 403 -> Audio.Denied
                        t.message != null -> Audio.Error(t.message.orEmpty())
                        else -> Audio.Unreachable
                    },
                )
            }
        })
        return try {
            withTimeoutOrNull(8_000) { answer.await() } ?: Audio.NoAnswer
        } finally {
            socket.close(1000, null)
        }
    }
}
