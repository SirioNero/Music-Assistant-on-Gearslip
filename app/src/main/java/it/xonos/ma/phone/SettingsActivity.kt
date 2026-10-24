package it.xonos.ma.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import it.xonos.ma.XonosApp
import it.xonos.ma.R
import it.xonos.ma.data.MaClient
import it.xonos.ma.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** La sola schermata sul telefono: indirizzi, token, nome del lettore e una prova di connessione. */
class SettingsActivity : Activity() {

    private val scope = MainScope()
    private lateinit var status: TextView
    private lateinit var battery: TextView
    private lateinit var batteryButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val app = application as XonosApp
        val home = findViewById<EditText>(R.id.url_home)
        val remote = findViewById<EditText>(R.id.url_remote)
        val token = findViewById<EditText>(R.id.token)
        val name = findViewById<EditText>(R.id.player_name)
        status = findViewById(R.id.status)
        battery = findViewById(R.id.battery)
        batteryButton = findViewById(R.id.battery_button)

        app.settings.load().let {
            home.setText(it.homeUrl)
            remote.setText(it.remoteUrl)
            token.setText(it.token)
        }
        name.setText(app.settings.playerName)

        findViewById<Button>(R.id.save).setOnClickListener { button ->
            val config = Settings.Config(
                homeUrl = Settings.normalizeUrl(home.text.toString()),
                remoteUrl = Settings.normalizeUrl(remote.text.toString()),
                token = token.text.toString().trim(),
            )
            home.setText(config.homeUrl)
            remote.setText(config.remoteUrl)
            app.settings.save(config)
            app.settings.playerName = name.text.toString()
            name.setText(app.settings.playerName)
            app.repo.reconnect()
            getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(button.windowToken, 0)
            test(app, config)
        }

        batteryButton.setOnClickListener { askBatteryExemption() }
    }

    override fun onResume() {
        super.onResume()
        updateBattery()
    }

    private fun test(app: XonosApp, config: Settings.Config) {
        if (!config.isComplete) {
            status.text = "Servono almeno un indirizzo e il token."
            return
        }
        status.text = "Provo la connessione…"
        scope.launch {
            val lines = mutableListOf<String>()
            for ((label, url) in listOf("Casa" to config.homeUrl, "Fuori casa" to config.remoteUrl)) {
                if (url.isBlank()) continue
                val client = app.repo.newClient()
                val line = try {
                    val server = client.connect(url, config.token)
                    val playlists = (client.command("music/playlists/count") as? Number)?.toInt()
                    val audio = testAudio(app, url, config.token)
                    listOfNotNull(
                        "✓ $label: Music Assistant ${server.version}",
                        playlists?.let { "$it playlist" },
                        audio,
                    ).joinToString(" · ")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "✗ $label: ${e.message ?: "errore sconosciuto"}"
                } finally {
                    client.close()
                }
                lines += line
                status.text = lines.joinToString("\n")
            }
        }
    }

    /** Apre /sendspin come farà il lettore e controlla che il server accetti il token. */
    private suspend fun testAudio(app: XonosApp, url: String, token: String): String {
        val answer = CompletableDeferred<String>()
        val request = Request.Builder().url(MaClient.websocketUrl(url, "/sendspin")).build()
        val socket = app.repo.http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "auth").put("token", token).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ok = runCatching { JSONObject(text).optString("type") == "auth_ok" }.getOrDefault(false)
                answer.complete(if (ok) "audio pronto" else "audio: risposta inattesa")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                answer.complete("audio: ${reason.ifBlank { "connessione chiusa" }}")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                answer.complete(
                    if (response?.code == 404) "audio: serve Music Assistant 2.7.5 o successivo"
                    else "audio: ${t.message ?: "non raggiungibile"}",
                )
            }
        })
        return try {
            withTimeoutOrNull(8_000) { answer.await() } ?: "audio: nessuna risposta"
        } finally {
            socket.close(1000, null)
        }
    }

    private fun updateBattery() {
        val power = getSystemService(PowerManager::class.java)
        val exempt = power?.isIgnoringBatteryOptimizations(packageName) == true
        battery.text = getString(if (exempt) R.string.battery_ok else R.string.battery_needed)
        batteryButton.visibility = if (exempt) View.GONE else View.VISIBLE
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        // Con l'ottimizzazione della batteria Android può fermare la musica a schermo spento.
        val intent = Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
