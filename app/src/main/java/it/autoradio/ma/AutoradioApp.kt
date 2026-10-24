package it.autoradio.ma

import android.app.Application
import it.autoradio.ma.data.Library
import it.autoradio.ma.data.MaRepository
import it.autoradio.ma.data.Settings

class AutoradioApp : Application() {
    val settings: Settings by lazy { Settings(this) }
    val repo: MaRepository by lazy { MaRepository(settings) }
    val library: Library by lazy { Library(repo) }
}
