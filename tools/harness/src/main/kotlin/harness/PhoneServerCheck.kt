package harness

import it.xonos.ma.data.Account
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.ConnectionCheck
import it.xonos.ma.phone.model.FixedFinder
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.PhoneServerControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URI

/**
 * "Fai diventare Xonos il tuo server Music Assistant" dal [PhoneModel], contro un Music Assistant appena
 * installato (MA_URL, deve essere vuoto: di solito il server di Xonos avviato sul computer, vedi
 * tools/server). Al posto del pacchetto scaricato e del suo processo, uno che finge il download e poi
 * aspetta solo che MA_URL risponda. Prova: la domanda con quanto pesa, il download con l'avanzamento, poi
 * da solo primo account creato da Xonos, token salvato, connessione provata; con il token perso, di nuovo
 * dentro con la password tenuta; "Usa un altro Music Assistant"; la rimozione; un download che non riesce
 * e un server che non parte.
 */
fun main(): Unit = runBlocking {
    val url = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    var failures = 0

    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }

    val settings = Settings().also { it.save(Settings.Config("", "", "")) }
    val repo = MaRepository(settings)
    repo.acquire()
    val model = PhoneModel(
        repo,
        Library(repo),
        settings,
        ScreenCache(null),
        FixedFinder(),
        "Xonos (prova)",
        { "xonos-phoneserver" },
        MutableStateFlow(false),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        WaitingServer(url),
    )

    var page = model.settingsPage()
    suspend fun settle() = withTimeoutOrNull(60_000) { while (page.phoneServerProgress != null || page.checking) delay(50) }

    check("C'è l'opzione", model.phoneServerAvailable)
    check("All'inizio non si usa e non è scaricato", !model.usesPhoneServer(page) && !page.phoneServerInstalled)
    model.askPhoneServerDownload(page)
    withTimeoutOrNull(5_000) { while (page.phoneServerSize == null) delay(20) }
    check("Prima la domanda, con quanto pesa", page.phoneServerAsk && page.phoneServerSize == WaitingServer.SIZE)
    val seen = mutableListOf<Pair<Long, Long>>()
    model.downloadPhoneServer(page)
    check("Detto sì: la domanda se ne va e si scarica", !page.phoneServerAsk && page.phoneServerProgress != null)
    withTimeoutOrNull(10_000) {
        while (page.phoneServerProgress != null) {
            page.phoneServerProgress?.let { if (seen.lastOrNull() != it) seen += it }
            delay(5)
        }
    }
    check("L'avanzamento arriva fino in fondo", seen.isNotEmpty() && seen.last().second == WaitingServer.SIZE, seen.joinToString())
    delay(100)
    settle()
    check("Scaricato", page.phoneServerInstalled)
    check(
        "Poi da solo: primo account creato da Xonos, token salvato e connessione provata",
        page.results.any { it is ConnectionCheck.Result.Ok } && settings.load().homeUrl == url && settings.load().token.isNotEmpty(),
        page.results.joinToString(),
    )
    check("Account e password tenuti", settings.username == PhoneModel.PHONE_SERVER_USER && !settings.phoneServerPassword.isNullOrEmpty())
    check("Il modulo indica il server sul telefono", model.usesPhoneServer(page))
    val first = settings.load().token

    // Token perso (o revocato): Xonos rientra con la password che si è tenuto, senza un altro account.
    settings.save(Settings.Config(url, "", ""))
    page = model.settingsPage()
    model.usePhoneServer(page)
    delay(100)
    settle()
    check(
        "Token perso: di nuovo dentro con la password tenuta",
        page.results.any { it is ConnectionCheck.Result.Ok } && settings.load().token.isNotEmpty() && settings.load().token != first,
        page.results.joinToString(),
    )
    check(
        "Dentro come amministratore",
        Account(repo).login(url, PhoneModel.PHONE_SERVER_USER, settings.phoneServerPassword.orEmpty(), "Xonos").role == "admin",
    )

    model.leavePhoneServer(page)
    check("Un altro Music Assistant: modulo vuoto", page.home.isEmpty() && page.token.isEmpty() && !model.usesPhoneServer(page))

    model.removePhoneServer(page)
    check("Tolto dal telefono: niente pacchetto né password", !page.phoneServerInstalled && settings.phoneServerPassword == null)

    // Un server che non parte: lo si dice, e il modulo non cambia.
    val broken = PhoneModel(
        repo,
        Library(repo),
        Settings().also { it.save(Settings.Config("", "", "")) },
        ScreenCache(null),
        FixedFinder(),
        "Xonos (prova)",
        { "xonos-phoneserver" },
        MutableStateFlow(false),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        BrokenServer,
    )
    page = broken.settingsPage()
    broken.downloadPhoneServer(page)
    delay(100)
    settle()
    check("Download non riuscito: il perché", page.phoneServerDownloadError == BrokenServer.DOWNLOAD && !page.phoneServerInstalled, "${page.phoneServerDownloadError}")
    broken.usePhoneServer(page)
    delay(100)
    settle()
    check("Server che non parte: il perché", page.phoneServerError == BrokenServer.error && !page.checking && page.home.isEmpty(), "${page.phoneServerError}")

    repo.release()
    println(if (failures == 0) "\nTutto bene" else "\n$failures prove non riuscite")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

/** Al posto del pacchetto e del suo processo: un download finto, poi aspetta che [url] risponda. */
private class WaitingServer(override val url: String) : PhoneServerControl {
    @Volatile override var state = PhoneServerControl.State.STOPPED
        private set
    override val error = ""
    @Volatile override var installed = false
        private set
    override val outdated = false

    override suspend fun downloadSize() = SIZE

    override suspend fun download(progress: (Long, Long) -> Unit) {
        var done = 0L
        while (done < SIZE) {
            done = minOf(SIZE, done + SIZE / 8)
            progress(done, SIZE)
            delay(20)
        }
        installed = true
    }

    override fun remove() {
        installed = false
    }

    override fun stop() {
        state = PhoneServerControl.State.STOPPED
    }

    override suspend fun start(): Boolean {
        state = PhoneServerControl.State.STARTING
        repeat(120) {
            val up = runCatching {
                val connection = URI("$url/info").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 1_000
                try {
                    connection.responseCode == 200
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
            if (up) {
                state = PhoneServerControl.State.RUNNING
                return true
            }
            delay(500)
        }
        state = PhoneServerControl.State.FAILED
        return false
    }

    companion object {
        const val SIZE = 70L * 1024 * 1024
    }
}

private object BrokenServer : PhoneServerControl {
    const val DOWNLOAD = "HTTP 404 for xonos-server-x86_64.zip"
    override val url = "http://127.0.0.1:1"
    override val state = PhoneServerControl.State.FAILED
    override val error = "AudioError: FFmpeg binary is missing from system."
    override val installed = false
    override val outdated = false

    override suspend fun downloadSize(): Long? = null

    override suspend fun download(progress: (Long, Long) -> Unit) = throw java.io.IOException(DOWNLOAD)

    override fun remove() = Unit

    override fun stop() = Unit

    override suspend fun start() = false
}
