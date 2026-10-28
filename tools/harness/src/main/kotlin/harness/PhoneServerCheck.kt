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
 * "Usa Music Assistant su questo telefono" dal [PhoneModel], contro un Music Assistant appena
 * installato (MA_URL, deve essere vuoto: di solito il server di Xonos avviato sul computer, vedi
 * tools/server). Al posto del server dentro l'app, uno che aspetta solo che MA_URL risponda.
 * Prova: primo account creato da Xonos, token salvato, connessione provata; con il token perso,
 * di nuovo dentro con la password tenuta; "Usa un altro Music Assistant"; un server che non parte.
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
    suspend fun settle() = withTimeoutOrNull(60_000) { while (page.checking) delay(50) }

    check("C'è l'opzione", model.phoneServerAvailable)
    check("All'inizio non si usa", !model.usesPhoneServer(page))
    model.usePhoneServer(page)
    delay(100)
    settle()
    check(
        "Primo account creato da Xonos, token salvato e connessione provata",
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
    broken.usePhoneServer(page)
    delay(100)
    settle()
    check("Server che non parte: il perché", page.phoneServerError == BrokenServer.error && !page.checking && page.home.isEmpty(), "${page.phoneServerError}")

    repo.release()
    println(if (failures == 0) "\nTutto bene" else "\n$failures prove non riuscite")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

/** Al posto del server dentro l'app: aspetta che [url] risponda. */
private class WaitingServer(override val url: String) : PhoneServerControl {
    @Volatile override var state = PhoneServerControl.State.STOPPED
        private set
    override val error = ""

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
}

private object BrokenServer : PhoneServerControl {
    override val url = "http://127.0.0.1:1"
    override val state = PhoneServerControl.State.FAILED
    override val error = "AudioError: FFmpeg binary is missing from system."

    override suspend fun start() = false
}
