package it.xonos.ma.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import it.xonos.ma.Language
import it.xonos.ma.R
import it.xonos.ma.XonosApp
import it.xonos.ma.data.MaClient
import it.xonos.ma.data.Settings
import it.xonos.ma.describe
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

/** La sola schermata sul telefono: lingua, indirizzi, token, nome del lettore e una prova di connessione. */
class SettingsActivity : Activity() {

    private val scope = MainScope()
    private lateinit var status: TextView
    private lateinit var battery: TextView
    private lateinit var batteryButton: Button

    // Le scritte nella lingua scelta qui, non in quella del telefono.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Settings(newBase).language.wrap(newBase))
    }

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

        setUpLanguage(app.settings)
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

    /** L'elenco delle lingue, ciascuna col suo nome: scelta una, la schermata si ridisegna in quella lingua. */
    private fun setUpLanguage(settings: Settings) {
        val languages = Language.entries
        val picker = findViewById<Spinner>(R.id.language)
        picker.adapter = ArrayAdapter(this, R.layout.item_language, languages.map { it.label }).apply {
            setDropDownViewResource(R.layout.item_language_dropdown)
        }
        picker.setSelection(languages.indexOf(settings.language), false)
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = languages[position]
                if (chosen == settings.language) return
                settings.language = chosen
                // Ciò che è già scritto nei campi resta: Android lo ricorda nel ridisegnare la schermata.
                recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun test(app: XonosApp, config: Settings.Config) {
        if (!config.isComplete) {
            status.text = getString(R.string.test_incomplete)
            return
        }
        status.text = getString(R.string.test_running)
        scope.launch {
            val lines = mutableListOf<String>()
            val places = listOf(getString(R.string.test_home) to config.homeUrl, getString(R.string.test_remote) to config.remoteUrl)
            for ((label, url) in places) {
                if (url.isBlank()) continue
                val client = app.repo.newClient()
                val line = try {
                    val server = client.connect(url, config.token)
                    val playlists = (client.command("music/playlists/count") as? Number)?.toInt()
                    val audio = testAudio(app, url, config.token)
                    listOfNotNull(
                        getString(R.string.test_ok, label, server.version),
                        playlists?.let { resources.getQuantityString(R.plurals.test_playlists, it, it) },
                        audio,
                    ).joinToString(" · ")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    getString(R.string.test_failed, label, describe(this@SettingsActivity, e))
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
                answer.complete(getString(if (ok) R.string.audio_ready else R.string.audio_unexpected))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                answer.complete(if (reason.isBlank()) getString(R.string.audio_closed) else getString(R.string.audio_error, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                answer.complete(
                    when {
                        response?.code == 404 -> getString(R.string.audio_too_old)
                        response?.code == 401 || response?.code == 403 -> getString(R.string.audio_denied)
                        t.message != null -> getString(R.string.audio_error, t.message)
                        else -> getString(R.string.audio_unreachable)
                    },
                )
            }
        })
        return try {
            withTimeoutOrNull(8_000) { answer.await() } ?: getString(R.string.audio_no_answer)
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
