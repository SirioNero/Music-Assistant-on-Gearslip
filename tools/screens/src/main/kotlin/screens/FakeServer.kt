package screens

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Color
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Un Music Assistant finto per le schermate: risponde all'accesso e ai comandi che le scene
 * chiedono ([answers]), e disegna le copertine (/imageproxy/<id>) come sfumature di colore.
 */
class FakeServer(private val answers: (String, JSONObject) -> Any?) {

    private val server = MockWebServer()

    /** I colori delle copertine, per id; le altre prendono un colore dal loro nome. */
    val covers = mutableMapOf<String, Pair<Int, Int>>()

    fun start(): String {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/ws") -> MockResponse().withWebSocketUpgrade(socket)
                    path.startsWith("/imageproxy/") -> {
                        val id = path.removePrefix("/imageproxy/").substringBefore('?')
                        val size = Regex("size=(\\d+)").find(path)?.groupValues?.get(1)?.toInt() ?: 256
                        MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(cover(id, size)))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        return server.url("/").toString().trimEnd('/')
    }

    private val socket = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(
                JSONObject().put("server_id", "screens").put("server_version", "2.10.8").put("schema_version", 65)
                    .put("name", "Music Assistant").toString(),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            val command = message.getString("command")
            val args = message.optJSONObject("args") ?: JSONObject()
            // Un amministratore: le impostazioni mostrano anche i servizi.
            val user = JSONObject().put("username", "sirio").put("display_name", "Sirio").put("role", "admin")
            val result = if (command == "auth") JSONObject().put("user", user) else answers(command, args) ?: JSONArray()
            webSocket.send(JSONObject().put("message_id", message.getString("message_id")).put("result", result).toString())
        }
    }

    private fun cover(id: String, size: Int): ByteArray {
        val (from, to) = covers[id] ?: (id.hashCode() and 0xFFFFFF).let { it to (it xor 0x404040) }
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, Color(from), size.toFloat(), size.toFloat(), Color(to))
        g.fillRect(0, 0, size, size)
        // Qualche cerchio, perché non sembrino tutte uguali.
        g.color = Color(255, 255, 255, 40)
        val r = size * (0.35f + (id.hashCode() and 7) / 40f)
        g.fillOval((size * 0.55f - r / 2).toInt(), (size * 0.45f - r / 2).toInt(), r.toInt(), r.toInt())
        g.color = Color(0, 0, 0, 50)
        g.fillOval((size * 0.15f).toInt(), (size * 0.6f).toInt(), (size * 0.3f).toInt(), (size * 0.3f).toInt())
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
