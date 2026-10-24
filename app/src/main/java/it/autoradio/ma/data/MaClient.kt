package it.autoradio.ma.data

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MaException(message: String, val code: Int? = null) : Exception(message)

/**
 * Una singola connessione WebSocket all'API di Music Assistant (/ws).
 * Gestisce l'accesso con il token, i comandi con la loro risposta e gli eventi del server.
 */
class MaClient(private val http: OkHttpClient) {

    private class Box(val value: Any?)

    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Box>>()
    private val partials = ConcurrentHashMap<String, JSONArray>()
    private val serverInfo = CompletableDeferred<JSONObject>()

    /** Si completa (con il motivo) quando la connessione si chiude. */
    val closed = CompletableDeferred<String>()

    /** Gli eventi del server (nome, object_id, dati). Arrivano sul thread di OkHttp. */
    @Volatile var onEvent: ((String, String?, Any?) -> Unit)? = null

    @Volatile private var socket: WebSocket? = null

    /** Si collega e accede con il token. */
    suspend fun connect(baseUrl: String, token: String, timeoutMs: Long = 10_000): ServerInfo {
        val request = try {
            Request.Builder().url(websocketUrl(baseUrl)).build()
        } catch (e: IllegalArgumentException) {
            throw MaException("Indirizzo non valido: $baseUrl")
        }
        socket = http.newWebSocket(request, listener)
        try {
            val info = withTimeoutOrNull(timeoutMs) { serverInfo.await() }
                ?: throw MaException("Nessuna risposta da $baseUrl")
            val server = ServerInfo.from(info) ?: throw MaException("A questo indirizzo non risponde Music Assistant")
            val auth = try {
                command("auth", timeoutMs) { put("token", token) } as? JSONObject
            } catch (e: MaException) {
                // Prima della 2.7 Music Assistant non aveva accessi né token, e nemmeno l'audio per i telefoni.
                if (e.message.orEmpty().contains("Invalid command")) {
                    throw MaException("Music Assistant ${server.version} è troppo vecchio: serve la 2.7 o successiva")
                }
                throw e
            }
            val filter = auth?.optJSONObject("user")?.optJSONArray("player_filter")
            return server.copy(limitedPlayers = filter != null && filter.length() > 0)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /**
     * Esegue un comando e ne restituisce il risultato (JSONObject, JSONArray, numero, stringa o null).
     * Gli elenchi lunghi arrivano a pezzi: qui si ricompongono.
     */
    suspend fun command(name: String, timeoutMs: Long = 30_000, fill: JSONObject.() -> Unit = {}): Any? {
        val ws = socket
        if (ws == null || closed.isCompleted) throw MaException("Non collegato a Music Assistant")
        val id = ids.getAndIncrement().toString()
        val message = JSONObject()
            .put("message_id", id)
            .put("command", name)
            .put("args", JSONObject().apply(fill))
        val deferred = CompletableDeferred<Box>()
        pending[id] = deferred
        if (!ws.send(message.toString())) {
            pending.remove(id)
            throw MaException("Invio a Music Assistant non riuscito")
        }
        val box = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (box == null) {
            pending.remove(id)
            partials.remove(id)
            throw MaException("Music Assistant non risponde")
        }
        return box.value
    }

    fun close() {
        socket?.close(1000, null)
        shutdown("Connessione chiusa")
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handle(JSONObject(text))
            } catch (e: Exception) {
                Log.w(TAG, "Messaggio non valido da Music Assistant", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            shutdown(reason.ifBlank { "Connessione chiusa" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val reason = when {
                response?.code == 401 || response?.code == 403 -> "Accesso negato"
                response?.code == 404 -> "A questo indirizzo non risponde Music Assistant"
                response != null -> "Errore HTTP ${response.code}"
                else -> t.message ?: "Errore di rete"
            }
            shutdown(reason)
        }
    }

    private fun handle(msg: JSONObject) {
        if (msg.has("event")) {
            val data = msg.opt("data")?.takeIf { it != JSONObject.NULL }
            try {
                onEvent?.invoke(msg.optString("event"), msg.str("object_id"), data)
            } catch (e: Exception) {
                Log.w(TAG, "Errore nella gestione di un evento", e)
            }
            return
        }
        val id = msg.str("message_id")
        if (id == null) {
            // Il primo messaggio: chi è il server.
            if (msg.has("server_id")) serverInfo.complete(msg)
            return
        }
        if (msg.has("error_code")) {
            val error = MaException(msg.str("details") ?: "Comando rifiutato", msg.optInt("error_code"))
            if (id == "connection") {
                // Per esempio "Setup required": il server chiude subito dopo.
                shutdown(error.message ?: "Connessione rifiutata")
                return
            }
            partials.remove(id)
            pending.remove(id)?.completeExceptionally(error)
            return
        }
        val result = msg.opt("result")?.takeIf { it != JSONObject.NULL }
        if (msg.optBoolean("partial")) {
            val chunk = result as? JSONArray ?: return
            val all = partials.getOrPut(id) { JSONArray() }
            for (i in 0 until chunk.length()) all.put(chunk.get(i))
            return
        }
        val deferred = pending.remove(id) ?: return
        val earlier = partials.remove(id)
        if (earlier != null && result is JSONArray) {
            for (i in 0 until result.length()) earlier.put(result.get(i))
            deferred.complete(Box(earlier))
        } else {
            deferred.complete(Box(earlier ?: result))
        }
    }

    private fun shutdown(reason: String) {
        val error = MaException(reason)
        serverInfo.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        partials.clear()
        closed.complete(reason)
    }

    companion object {
        private const val TAG = "Autoradio"

        /** L'indirizzo WebSocket di un percorso del server: http diventa ws, https diventa wss. */
        fun websocketUrl(base: String, path: String = "/ws"): String {
            val trimmed = base.trim().trimEnd('/')
            val ws = when {
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                else -> "ws://$trimmed"
            }
            return ws + path
        }
    }
}
