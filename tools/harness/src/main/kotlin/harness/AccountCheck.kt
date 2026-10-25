package harness

import it.xonos.ma.data.Account
import it.xonos.ma.data.MaException
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Problem
import it.xonos.ma.data.Settings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.Base64

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
        check("Server nuovo: password corta", problemOf { account.createFirst(fresh, "primo", "corta", "Xonos") } == Problem.REFUSED)
        val first = account.createFirst(fresh, "primo", "primo-xonos", "Xonos (primo)")
        check("Server nuovo: primo account amministratore", first.role == "admin" && claims(first.token).optBoolean("is_long_lived"), first.user)
        check("Server nuovo: poi si entra", account.login(fresh, "primo", "primo-xonos", "Xonos").role == "admin")
    }

    println(if (failures == 0) "\nTutto bene" else "\n$failures prove non riuscite")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}

/** Il contenuto di un token JWT di Music Assistant (senza verificarlo). */
private fun claims(token: String): JSONObject =
    runCatching { JSONObject(String(Base64.getUrlDecoder().decode(token.split('.')[1]))) }.getOrDefault(JSONObject())
