package it.xonos.ma

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import it.xonos.ma.server.LocalServer
import kotlin.concurrent.thread

/**
 * Solo nella build di prova: avvia il server di Music Assistant dentro Xonos e mostra se gira.
 * Il workflow "Server MA" la apre con `am start`, poi parla col server da fuori (adb forward).
 * Nel registro (tag XonosServer) ogni cambio di stato, con i secondi dall'avvio.
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
        val context = applicationContext
        thread(name = "server-check") {
            val started = SystemClock.elapsedRealtime()
            LocalServer.start(context)
            var last: LocalServer.State? = null
            while (open) {
                val state = LocalServer.state()
                if (state != last) {
                    val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
                    val line = "Server: $state dopo $seconds s ${LocalServer.error()}".trim()
                    Log.i("XonosServer", line)
                    runOnUiThread { text.text = "$line\n${LocalServer.URL}" }
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
}
