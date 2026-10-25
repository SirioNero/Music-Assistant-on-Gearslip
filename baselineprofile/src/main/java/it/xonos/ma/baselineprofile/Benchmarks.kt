@file:OptIn(ExperimentalMetricApi::class)

package it.xonos.ma.baselineprofile

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Le misure di Xonos sull'emulatore: avvio a freddo fino alla Home piena, scorrimento di Home e
 * Libreria, apertura del lettore con i testi, una playlist da 2880 brani. Ciascuna per ogni
 * compilazione di [Xonos.modes]. Le sezioni "Xonos.items@main" e "Xonos.json" dicono quanto
 * lavoro di conversione finisce sul thread principale e quanto costa leggere il JSON.
 */
abstract class XonosBenchmark(protected val mode: String) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Before
    fun prepare() = Xonos.prepare()

    protected val conversion = listOf(
        TraceSectionMetric("Xonos.items@main", TraceSectionMetric.Mode.Sum),
        TraceSectionMetric("Xonos.items", TraceSectionMetric.Mode.Sum),
        TraceSectionMetric("Xonos.json", TraceSectionMetric.Mode.Sum),
    )

    /** Dove va il tempo dell'avvio sul thread principale: l'app, l'Activity, il servizio di riproduzione. */
    protected val startup = listOf(
        TraceSectionMetric("bindApplication", TraceSectionMetric.Mode.First),
        TraceSectionMetric("activityStart", TraceSectionMetric.Mode.First),
        TraceSectionMetric("serviceCreate%", TraceSectionMetric.Mode.Sum, label = "serviceCreate"),
        TraceSectionMetric("Choreographer#doFrame%", TraceSectionMetric.Mode.Sum, label = "frames"),
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<String> = Xonos.modes
    }
}

/** Dall'icona alla Home con i dati (timeToFullDisplay: la Home dice quando è pronta). */
@RunWith(Parameterized::class)
class StartupBenchmark(mode: String) : XonosBenchmark(mode) {
    @Test
    fun startup() = rule.measureRepeated(
        packageName = Xonos.packageName,
        metrics = listOf(StartupTimingMetric()) + startup + conversion,
        compilationMode = Xonos.compilation(mode),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        waitFor("row", 60_000)
    }
}

/** La Home già caricata: giù e su, e una riga di schede di lato. */
@RunWith(Parameterized::class)
class HomeScrollBenchmark(mode: String) : XonosBenchmark(mode) {
    @Test
    fun scroll() = rule.measureRepeated(
        packageName = Xonos.packageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = Xonos.compilation(mode),
        iterations = 5,
        setupBlock = { openHome() },
    ) {
        flingList("home", 3)
        device.findObject(By.res("row"))?.let { row ->
            row.setGestureMargin(device.displayWidth / 10)
            runCatching {
                row.fling(Direction.RIGHT)
                row.fling(Direction.LEFT)
            }
        }
    }
}

/** La Libreria: la griglia degli album e l'elenco dei brani, che si caricano a pagine scorrendo. */
@RunWith(Parameterized::class)
class LibraryScrollBenchmark(mode: String) : XonosBenchmark(mode) {
    @Test
    fun scroll() = rule.measureRepeated(
        packageName = Xonos.packageName,
        metrics = listOf(FrameTimingMetric()) + conversion,
        compilationMode = Xonos.compilation(mode),
        iterations = 5,
        setupBlock = { openHome() },
    ) {
        openShelf("Albums")
        waitFor("card", 30_000)
        flingList("library_list", 4)
        openShelf("Tracks")
        waitFor("item_row", 30_000)
        flingList("library_list", 4)
    }
}

/** Il lettore a schermo intero: si apre, mostra il testo che scorre con la musica, si chiude. */
@RunWith(Parameterized::class)
class PlayerBenchmark(mode: String) : XonosBenchmark(mode) {
    @Test
    fun open() = rule.measureRepeated(
        packageName = Xonos.packageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = Xonos.compilation(mode),
        iterations = 5,
        setupBlock = {
            openHome()
            ensurePlaying()
        },
    ) {
        waitFor("mini_player").click()
        waitFor("player")
        Thread.sleep(1_000)
        device.findObject(By.desc("Lyrics"))?.click()
        Thread.sleep(4_000)
        device.pressBack()
        device.wait(Until.gone(By.res("player")), 5_000)
    }
}

/** Una playlist da 2880 brani: quanto ci vuole a vederla, quanto lavoro sul thread principale, quanta memoria. */
@RunWith(Parameterized::class)
class PlaylistBenchmark(mode: String) : XonosBenchmark(mode) {
    @Test
    fun open() = rule.measureRepeated(
        packageName = Xonos.packageName,
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)) + conversion,
        compilationMode = Xonos.compilation(mode),
        iterations = 3,
        setupBlock = { openHome() },
    ) {
        openShelf("Playlists")
        waitFor("card", 30_000)
        scrollToText("library_list", Xonos.PLAYLIST).click()
        waitFor("item_list")
        waitFor("item_row", 120_000)
        flingList("item_list", 3)
    }
}
