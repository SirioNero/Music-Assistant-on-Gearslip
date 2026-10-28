package it.xonos.ma

import android.content.Context
import android.os.SystemClock
import it.xonos.ma.phone.model.PhoneServerControl
import it.xonos.ma.phone.model.PhoneServerControl.State
import it.xonos.ma.server.LocalServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Music Assistant sul telefono: questa versione di Xonos lo porta dentro di sé (modulo server). */
object PhoneServer {
    /** Il primo avvio dopo un aggiornamento estrae i file: su un telefono lento ci mette un po'. */
    private const val START_TIMEOUT_MS = 180_000L
    private const val POLL_MS = 250L

    fun create(context: Context): PhoneServerControl = Control(context.applicationContext)

    private class Control(private val context: Context) : PhoneServerControl {
        override val url = LocalServer.URL

        override val state: State
            get() = when (LocalServer.state()) {
                LocalServer.State.STARTING -> State.STARTING
                LocalServer.State.RUNNING -> State.RUNNING
                LocalServer.State.FAILED -> State.FAILED
                LocalServer.State.STOPPED -> State.STOPPED
            }

        override val error: String get() = LocalServer.error()

        override suspend fun start(): Boolean = withContext(Dispatchers.IO) {
            LocalServer.start(context)
            val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                when (state) {
                    State.RUNNING -> return@withContext true
                    State.FAILED, State.STOPPED -> return@withContext false
                    State.STARTING -> delay(POLL_MS)
                }
            }
            false
        }
    }
}
