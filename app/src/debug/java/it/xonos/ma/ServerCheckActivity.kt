package it.xonos.ma

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Solo nella build di prova: avvia Music Assistant sul telefono e mostra se gira (nella versione senza
 * server lo dice). Il workflow "Server MA" la apre con `am start`, poi parla col server da fuori (adb
 * forward). Nel registro (tag XonosServer) ogni cambio di stato, con i secondi dall'avvio.
 */
class ServerCheckActivity : Activity() {
    @Volatile private var open = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 18f
        }
        setContentView(text)
        val server = (application as XonosApp).phoneServer
        if (server == null) {
            text.text = "Questa versione di Xonos non ha Music Assistant dentro di sé."
            Log.i(TAG, "Server: STOPPED (questa versione non lo ha)")
            return
        }
        thread(name = "server-check") {
            val started = SystemClock.elapsedRealtime()
            thread(name = "server-start") { runBlocking { server.start() } }
            var last: String? = null
            while (open) {
                val state = server.state.name
                if (state != last) {
                    val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
                    val line = "Server: $state dopo $seconds s ${server.error}".trim()
                    Log.i(TAG, line)
                    runOnUiThread { text.text = "$line\n${server.url}" }
                    last = state
                }
                Thread.sleep(500)
            }
        }
    }

    override fun onDestroy() {
        open = false
        super.onDestroy()
    }

    private companion object {
        const val TAG = "XonosServer"
    }
}
