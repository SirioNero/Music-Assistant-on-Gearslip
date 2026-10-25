package it.xonos.ma.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il Baseline Profile di Xonos: i percorsi che contano, cioè l'avvio fino alla Home, lo
 * scorrimento di Home e Libreria, un album, il lettore con i testi e la ricerca. Android compila
 * in anticipo il codice che ci passa, invece di interpretarlo le prime volte.
 *
 * Si genera con `gradle :app:generateBaselineProfile` (vedi tools/measure/run.sh) e finisce in
 * app/src/main/generated/baselineProfiles.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = Xonos.packageName, includeInStartupProfile = true) {
        openHome()
        ensurePlaying()
        flingList("home", 2)

        openShelf("Albums")
        waitFor("card", 30_000).click()
        waitFor("item_row", 30_000)
        flingList("item_list", 1)
        device.pressBack()
        flingList("library_list", 2)
        openShelf("Tracks")
        flingList("library_list", 2)

        waitFor("mini_player").click()
        waitFor("player")
        device.findObject(By.desc("Lyrics"))?.click()
        Thread.sleep(2_000)
        device.findObject(By.desc("Up next"))?.click()
        Thread.sleep(1_000)
        device.pressBack()
        device.wait(Until.gone(By.res("player")), 5_000)

        waitForText("Search").click()
        waitFor("search_field").text = "vasco"
        waitFor("top_result", 45_000)
        device.wait(Until.hasObject(By.res("row")), 10_000)
    }
}
