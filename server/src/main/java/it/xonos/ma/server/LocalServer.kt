package it.xonos.ma.server

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * Il server di Music Assistant dentro Xonos: Python 3.13 (Chaquopy) con xonos_server.py.
 *
 * Risponde solo sul telefono stesso, a [URL], come un Music Assistant qualsiasi: Xonos (e myCar
 * attraverso Xonos) ci si collegano come a quello di casa. Account, servizi e libreria stanno nella
 * cartella dell'app. Le funzioni aspettano Python: vanno chiamate fuori dal thread principale.
 */
object LocalServer {
    const val URL = "http://127.0.0.1:8095"
    private const val TAG = "XonosServer"
    private val TOOLS = listOf("ffmpeg", "ffprobe")

    enum class State { STOPPED, STARTING, RUNNING, FAILED }

    /** Avvia il server e torna subito; se gira già non fa niente. Dopo un aggiornamento dell'app il primo avvio estrae i file. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (!Python.isStarted()) Python.start(AndroidPlatform(app))
        val libraries = app.applicationInfo.nativeLibraryDir
        module().callAttr(
            "start",
            File(app.filesDir, "music-assistant").path,
            File(app.cacheDir, "music-assistant").path,
            linkTools(app, libraries).path,
            libraries,
        )
        Log.i(TAG, "Avvio di Music Assistant sul telefono")
    }

    fun state(): State {
        if (!Python.isStarted()) return State.STOPPED
        return when (module().callAttr("state").toString()) {
            "starting" -> State.STARTING
            "running" -> State.RUNNING
            "failed" -> State.FAILED
            else -> State.STOPPED
        }
    }

    /** Perché il server si è fermato o non è partito, se è successo. */
    fun error(): String = if (Python.isStarted()) module().callAttr("error").toString() else ""

    /** Ferma il server e aspetta che si chiuda, al massimo 15 secondi. */
    fun stop() {
        if (Python.isStarted()) module().callAttr("stop")
    }

    private fun module(): PyObject = Python.getInstance().getModule("xonos_server")

    /**
     * Music Assistant chiama ffmpeg e ffprobe per nome. Android li installa con le librerie dell'app,
     * l'unico posto da cui un'app può eseguire un programma, ma solo con nomi lib*.so: qui una cartella
     * con i nomi giusti che rimandano là. Si rifà a ogni avvio, perché quel posto cambia a ogni aggiornamento.
     */
    private fun linkTools(context: Context, libraries: String): File {
        val tools = File(context.filesDir, "music-assistant-tools").apply { mkdirs() }
        for (name in TOOLS) {
            val link = File(tools, name)
            try {
                link.delete()
                Os.symlink(File(libraries, "lib$name.so").path, link.path)
            } catch (e: ErrnoException) {
                Log.w(TAG, "Non riesco a preparare $name: ${e.message}")
            }
        }
        return tools
    }
}
