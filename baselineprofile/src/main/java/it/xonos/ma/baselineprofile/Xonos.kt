package it.xonos.ma.baselineprofile

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

/**
 * L'app da guidare e il Music Assistant di prova. Gli argomenti arrivano dal workflow "Misure"
 * (tools/measure/run.sh): indirizzo, token, quali compilazioni provare.
 */
object Xonos {
    private val args get() = InstrumentationRegistry.getArguments()

    val packageName: String get() = args.getString("targetAppId") ?: "it.xonos.ma"
    val url: String get() = args.getString("xonosUrl") ?: "http://10.0.2.2:8095"
    val token: String get() = args.getString("xonosToken").orEmpty()
    val username: String get() = args.getString("xonosUser") ?: "prova"
    val password: String get() = args.getString("xonosPassword") ?: "prova-xonos"

    /** "none" (nessuna compilazione: come appena installata) e "partial" (con il Baseline Profile). */
    val modes: List<String> get() = (args.getString("xonosModes") ?: "none").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** La playlist da 2880 brani che il workflow aggiunge al Music Assistant di prova. */
    const val PLAYLIST = "Playlist grande"

    /** L'album con i brani da un minuto e i testi: quello che suona durante le misure. */
    const val ALBUM = "Emozioni"

    fun compilation(mode: String): CompilationMode = when (mode) {
        "partial" -> CompilationMode.Partial(BaselineProfileMode.Require)
        "full" -> CompilationMode.Full()
        else -> CompilationMode.None()
    }

    @Volatile private var prepared = false

    /** Una volta sola: collega l'app al Music Assistant di prova e fa suonare l'album (serve il mini-lettore). */
    fun prepare() {
        if (prepared) return
        MacrobenchmarkScope(packageName, launchWithClearTask = true).run {
            openHome()
            ensurePlaying()
            pressHome()
        }
        prepared = true
    }
}

/** L'elemento con questo testTag, aspettandolo; se non arriva un errore che dice quale. */
fun MacrobenchmarkScope.waitFor(tag: String, timeoutMs: Long = 20_000): UiObject2 =
    device.wait(Until.findObject(By.res(tag)), timeoutMs) ?: missing("\"$tag\"", timeoutMs)

/** Il testo dato (una scheda, una voce della libreria...), aspettandolo. */
fun MacrobenchmarkScope.waitForText(text: String, timeoutMs: Long = 20_000): UiObject2 =
    device.wait(Until.findObject(By.text(text)), timeoutMs) ?: missing("il testo \"$text\"", timeoutMs)

/** Qualcosa non è comparso: nel log va lo schermo com'è (la gerarchia vista da UiAutomator), poi l'errore. */
private fun MacrobenchmarkScope.missing(what: String, timeoutMs: Long): Nothing {
    val dump = ByteArrayOutputStream().also { device.dumpWindowHierarchy(it) }.toString()
    dump.chunked(3_000).forEach { Log.e("XonosMisure", it) }
    error("Non compare $what entro $timeoutMs ms")
}

/** Un dialogo "... non risponde" del sistema (sull'emulatore succede al launcher): si aspetta. */
fun MacrobenchmarkScope.dismissSystemDialog() {
    if (device.hasObject(By.textContains("isn't responding"))) device.findObject(By.text("Wait"))?.click()
}

/** Apre l'app sulla Home con i dati; la prima volta la collega al Music Assistant di prova. */
fun MacrobenchmarkScope.openHome() {
    dismissSystemDialog()
    startActivityAndWait()
    connectIfNeeded()
    // Se l'app ricorda un'altra scheda si torna alla Home.
    device.findObject(By.text("Home"))?.click()
    waitFor("row", 60_000)
}

/** Il benvenuto: indirizzo e token del Music Assistant di prova, "Salva" e poi "Vai alla tua musica". */
private fun MacrobenchmarkScope.connectIfNeeded() {
    // Il primo avvio dopo l'installazione è lento: si aspetta il benvenuto o la Home, quello che arriva.
    device.wait(Until.hasObject(By.res(Pattern.compile("field_home|home"))), 30_000)
    val home = device.findObject(By.res("field_home")) ?: return
    home.text = Xonos.url
    val username = device.findObject(By.res("field_username"))
    if (username != null && Xonos.token.isEmpty()) {
        // Come farebbe una persona: nome utente e password, e Xonos si fa dare il token.
        username.text = Xonos.username
        waitFor("field_password").text = Xonos.password
    } else {
        device.findObject(By.res("login_token"))?.click()
        waitFor("field_token").text = Xonos.token
    }
    scrollToVisible("save").click()
    scrollToVisible("onboarding_done", 60_000).click()
}

/** Scorre in giù finché l'elemento non è sullo schermo (i moduli sono più lunghi dello schermo). */
private fun MacrobenchmarkScope.scrollToVisible(tag: String, timeoutMs: Long = 20_000): UiObject2 {
    val end = SystemClock.uptimeMillis() + timeoutMs
    while (SystemClock.uptimeMillis() < end) {
        val found = device.findObject(By.res(tag))
        if (found != null && found.visibleBounds.height() > 8) return found
        device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.6f)
        Thread.sleep(300)
    }
    missing("\"$tag\"", timeoutMs)
}

/** Se la coda del telefono è vuota fa suonare l'album di prova, cercandolo. */
fun MacrobenchmarkScope.ensurePlaying() {
    if (device.wait(Until.hasObject(By.res("mini_player")), 3_000)) return
    waitForText("Search").click()
    waitFor("search_field").text = "album ${Xonos.ALBUM}"
    val top = waitFor("top_result", 45_000)
    (top.findObject(By.desc("Play")) ?: error("Il risultato migliore non ha il tasto Play")).click()
    waitFor("mini_player", 45_000)
    waitForText("Home").click()
}

/**
 * Scorre l'elenco con questo testTag: [times] colpi in giù e uno su. L'elenco si ritrova prima di
 * ogni colpo: mentre arrivano i dati Compose può rifarne il nodo, e quello vecchio non vale più.
 */
fun MacrobenchmarkScope.flingList(tag: String, times: Int) {
    repeat(times) { fling(tag, Direction.DOWN) }
    fling(tag, Direction.UP)
}

private fun MacrobenchmarkScope.fling(tag: String, direction: Direction) {
    val list = waitFor(tag)
    // Lontano dai bordi, dove il gesto indietro di Android ruberebbe il tocco.
    list.setGestureMargin(device.displayWidth / 5)
    try {
        list.fling(direction)
    } catch (e: StaleObjectException) {
        waitFor(tag).fling(direction)
    }
    device.waitForIdle()
}

/** Una voce della libreria (Albums, Tracks, Playlists...): la scheda Libreria e il suo filtro. */
fun MacrobenchmarkScope.openShelf(name: String) {
    waitForText("Library").click()
    waitForText(name, 30_000).click()
    waitFor("library_list", 30_000)
}

/** Un elemento dell'elenco [tag] con questo testo, scorrendo in giù finché non compare. */
fun MacrobenchmarkScope.scrollToText(tag: String, text: String): UiObject2 {
    repeat(15) {
        device.findObject(By.text(text))?.let { return it }
        waitFor(tag).scroll(Direction.DOWN, 0.8f)
    }
    return waitForText(text)
}
