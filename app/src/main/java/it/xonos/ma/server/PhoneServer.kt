package it.xonos.ma.server

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import it.xonos.ma.phone.model.PhoneServerControl
import it.xonos.ma.phone.model.PhoneServerControl.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Music Assistant sul telefono: il pacchetto scaricato ([ServerPackage]) avviato come processo a sé,
 * `python3 -m xonos_server` (server/src/main/python/xonos_server.py). Da Android 10 un'app non può avviare i
 * programmi che ha scaricato: li fa partire il linker di sistema (`/system/bin/linker64 <programma>`), anche
 * ffmpeg, ffprobe e librespot quando li chiama Music Assistant. Il processo vive quanto Xonos; i dati di
 * Music Assistant (account, servizi, libreria) restano nella cartella dell'app anche quando il pacchetto cambia.
 */
class PhoneServer private constructor(context: Context, private val pkg: ServerPackage) : PhoneServerControl {

    private val data = File(context.filesDir, "music-assistant")
    private val cache = File(context.cacheDir, "music-assistant")
    private val lock = Any()
    private var process: Process? = null
    private var stopping = false

    override val url = URL

    @Volatile
    override var state = State.STOPPED
        private set

    @Volatile
    override var error = ""
        private set

    override val installed: Boolean get() = pkg.installed

    override val outdated: Boolean get() = pkg.outdated

    override suspend fun downloadSize(): Long? = pkg.size()

    override suspend fun download(progress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) { stop() }
        pkg.download(progress)
    }

    // Fermarlo aspetta Music Assistant, e il pacchetto estratto sono migliaia di file: niente sul thread dello schermo.
    override suspend fun remove() = withContext(Dispatchers.IO) {
        stop()
        pkg.remove()
        data.deleteRecursively()
        cache.deleteRecursively()
        Unit
    }

    override suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        launch()
        val deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (state) {
                State.RUNNING -> return@withContext true
                State.FAILED, State.STOPPED -> return@withContext false
                State.STARTING -> delay(POLL_MS)
            }
        }
        error = "it didn't start in ${START_TIMEOUT_MS / 1000} s"
        false
    }

    override fun stop() {
        val running = synchronized(lock) {
            stopping = true
            process
        } ?: return
        // SIGTERM: Music Assistant chiude il database e i servizi; se non basta, SIGKILL.
        running.destroy()
        if (!running.waitFor(STOP_TIMEOUT_S, TimeUnit.SECONDS)) running.destroyForcibly()
    }

    private fun launch() {
        synchronized(lock) { launchLocked() }
    }

    private fun launchLocked() {
        if (process?.isAlive == true) return
        if (!pkg.installed) {
            state = State.FAILED
            error = "Music Assistant isn't on this phone"
            return
        }
        stopping = false
        state = State.STARTING
        error = ""
        val dir = pkg.dir
        val tmp = File(cache, "tmp").apply { mkdirs() }
        data.mkdirs()
        val command = buildList {
            if (Build.VERSION.SDK_INT >= W_X_SDK) add(LINKER)
            add(File(dir, "bin/python3").path)
            addAll(listOf("-m", "xonos_server", "--data", data.path, "--cache", cache.path))
        }
        val builder = ProcessBuilder(command).redirectErrorStream(true).directory(data)
        builder.environment().apply {
            put("PYTHONHOME", dir.path)
            put("PYTHONPATH", "$dir/app:$dir/site-packages")
            put("LD_LIBRARY_PATH", "$dir/lib:$dir/site-packages/chaquopy/lib")
            put("PATH", "$dir/bin:" + (get("PATH") ?: "/system/bin"))
            put("XONOS_BIN", "$dir/bin")
            if (Build.VERSION.SDK_INT >= W_X_SDK) put("XONOS_LINKER", LINKER)
            put("SSL_CERT_FILE", "$dir/lib/cacert.pem")
            put("HOME", data.path)
            put("TMPDIR", tmp.path)
            put("LANG", "C.UTF-8")
            put("PYTHONUTF8", "1")
            put("PYTHONUNBUFFERED", "1")
            put("PYTHONDONTWRITEBYTECODE", "1")
        }
        val started = try {
            builder.start()
        } catch (e: Exception) {
            state = State.FAILED
            error = e.message ?: e.toString()
            Log.w(TAG, "Music Assistant non parte", e)
            return
        }
        process = started
        Log.i(TAG, "Avvio di Music Assistant sul telefono")
        thread(name = "music-assistant-log", isDaemon = true) { follow(started) }
    }

    /** Il registro del processo nel logcat; le righe XONOS_STATE dicono a che punto è. */
    private fun follow(running: Process) {
        val last = ArrayDeque<String>()
        runCatching {
            running.inputStream.bufferedReader().forEachLine { line ->
                if (line.startsWith(STATE_PREFIX)) {
                    val said = line.removePrefix(STATE_PREFIX).trim()
                    when {
                        said == "running" -> state = State.RUNNING
                        said == "starting" -> state = State.STARTING
                        said == "stopped" -> state = State.STOPPED
                        said.startsWith("failed") -> {
                            error = said.removePrefix("failed").trim()
                            state = State.FAILED
                        }
                    }
                } else {
                    Log.i(TAG, line)
                    last.addLast(line)
                    if (last.size > LAST_LINES) last.removeFirst()
                }
            }
        }
        val code = running.waitFor()
        synchronized(lock) {
            if (process === running) process = null
            if (stopping) {
                state = State.STOPPED
            } else if (state != State.FAILED) {
                state = State.FAILED
                error = "it stopped (exit $code): ${last.lastOrNull().orEmpty()}".trim()
            }
        }
        Log.i(TAG, "Music Assistant sul telefono si è fermato ($code)")
    }

    companion object {
        const val URL = "http://127.0.0.1:8095"
        private const val TAG = "XonosServer"
        private const val LINKER = "/system/bin/linker64"
        /** Android 10: da qui le app non avviano più i programmi che scaricano (W^X). */
        private const val W_X_SDK = 29
        private const val STATE_PREFIX = "XONOS_STATE"
        /** Il primo avvio dopo il download prepara il database: su un telefono lento ci vuole un po'. */
        private const val START_TIMEOUT_MS = 180_000L
        private const val POLL_MS = 250L
        private const val STOP_TIMEOUT_S = 15L
        private const val LAST_LINES = 20

        /** Music Assistant sul telefono, dove può girare (un telefono a 64 bit); altrimenti null. */
        fun create(context: Context, http: OkHttpClient): PhoneServerControl? {
            val pkg = ServerPackage(context.applicationContext, http)
            return if (pkg.abi != null) PhoneServer(context.applicationContext, pkg) else null
        }
    }
}
