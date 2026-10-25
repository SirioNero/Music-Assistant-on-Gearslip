package harness

import it.xonos.ma.data.Account
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaException
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Problem
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.ConnectionCheck
import it.xonos.ma.phone.model.FixedFinder
import it.xonos.ma.phone.model.PhoneModel
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * L'accesso con nome utente e password ([Account]) contro Music Assistant: utente giusto e
 * sbagliato, il token a lunga durata che Xonos si fa dare (e che poi funziona), un ospite, e il
 * primo account di un Music Assistant appena installato (NEW_URL, se c'è: deve essere vuoto).
 * Variabili: MA_URL, MA_USER, MA_PASSWORD (il primo utente di setup.py), MA_TOKEN, NEW_URL.
 */
fun main(): Unit = runBlocking {
    val url = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    val user = System.getenv("MA_USER") ?: "prova"
    val password = System.getenv("MA_PASSWORD") ?: "prova-xonos"
    val repo = MaRepository(Settings())
    val account = Account(repo)
    var failures = 0

    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }

    suspend fun problemOf(block: suspend () -> Unit): Problem? = try {
        block()
        null
    } catch (e: MaException) {
        println("   ${e.problem}: ${e.message}")
        e.problem
    }

    val login = account.login(url, user, password, "Xonos (prova accesso)")
    check("Accesso: token e ruolo", login.token.isNotBlank() && login.role == "admin", "${login.user} (${login.role})")
    check("Accesso: il token è a lunga durata", claims(login.token).optBoolean("is_long_lived"), "${claims(login.token)}")
    val server = repo.newClient().let { client ->
        try {
            client.connect(url, login.token)
        } finally {
            client.close()
        }
    }
    check("Accesso: con il token nuovo ci si collega", server.admin, "${server.user} ${server.role}")
    check("Accesso: password sbagliata", problemOf { account.login(url, user, "sbagliata", "Xonos") } == Problem.BAD_LOGIN)
    check("Accesso: utente che non c'è", problemOf { account.login(url, "nessuno", "nessuna-password", "Xonos") } == Problem.BAD_LOGIN)

    // Un ospite (lo crea l'amministratore): niente token a lunga durata, resta quello della sessione.
    val admin = repo.newClient()
    admin.connect(url, login.token)
    runCatching {
        admin.command("auth/user/create") {
            put("username", "ospite")
            put("password", "ospite-xonos")
            put("role", "guest")
        }
    }.onFailure { println("   l'ospite c'è già? ${it.message}") }
    admin.close()
    val guest = account.login(url, "ospite", "ospite-xonos", "Xonos (ospite)")
    check("Ospite: accesso", guest.role == "guest", guest.user)
    check("Ospite: token della sessione", !claims(guest.token).optBoolean("is_long_lived"))

    val fresh = System.getenv("NEW_URL")
    if (fresh != null) {
        check("Server nuovo: serve il primo account", problemOf { account.login(fresh, "chiunque", "qualsiasi-password", "Xonos") } == Problem.SETUP_REQUIRED)
        check("Server nuovo: password corta rifiutata dal server", problemOf { account.createFirst(fresh, "primo", "corta", "Xonos") } == Problem.REFUSED)

        // Come dallo schermo del benvenuto: si prova ad accedere, si scopre che non ci sono account, si crea il primo.
        val freshSettings = Settings().also { it.save(Settings.Config(fresh, "", "")) }
        val freshRepo = MaRepository(freshSettings)
        freshRepo.acquire()
        val model = PhoneModel(
            freshRepo,
            Library(freshRepo),
            freshSettings,
            ScreenCache(null),
            FixedFinder(),
            "Xonos (prova)",
            { "xonos-accountcheck" },
            MutableStateFlow(false),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val page = model.settingsPage()
        page.useToken = false
        page.username = "primo"
        page.password = "primo-xonos"
        suspend fun settle() = withTimeoutOrNull(30_000) { while (page.checking) delay(50) }
        model.save(page)
        delay(100)
        settle()
        check("Schermo: propone di creare il primo account", page.setupNeeded, page.results.joinToString())
        page.password = "corta"
        model.createAccount(page)
        check("Schermo: password troppo corta", page.passwordShort && !page.checking)
        page.password = "primo-xonos"
        model.createAccount(page)
        delay(100)
        settle()
        check(
            "Schermo: primo account creato, token salvato e connessione provata",
            page.results.any { it is ConnectionCheck.Result.Ok } && freshSettings.username == "primo" && freshSettings.load().token.isNotEmpty() && !page.setupNeeded,
            page.results.joinToString(),
        )
        check("Server nuovo: poi si entra", account.login(fresh, "primo", "primo-xonos", "Xonos").role == "admin")
        freshRepo.release()
    }

    println(if (failures == 0) "\nTutto bene" else "\n$failures prove non riuscite")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

/** Il contenuto di un token JWT di Music Assistant (senza verificarlo). */
private fun claims(token: String): JSONObject =
    runCatching { JSONObject(String(Base64.getUrlDecoder().decode(token.split('.')[1]))) }.getOrDefault(JSONObject())
