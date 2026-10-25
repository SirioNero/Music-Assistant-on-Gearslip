package screens

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import it.xonos.ma.Language
import it.xonos.ma.R
import it.xonos.ma.data.Connection
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaException
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.Problem
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.ArtistPage
import it.xonos.ma.phone.model.ConnectionCheck
import it.xonos.ma.phone.model.HomePage
import it.xonos.ma.phone.model.ItemPage
import it.xonos.ma.phone.model.LibraryPage
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.NowPlaying
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.PlayerLink
import it.xonos.ma.phone.model.PlayerPanel
import it.xonos.ma.phone.model.QueueEntry
import it.xonos.ma.phone.model.Repeat
import it.xonos.ma.phone.model.SearchPage
import it.xonos.ma.phone.model.SearchResults
import it.xonos.ma.phone.model.Shelf
import it.xonos.ma.phone.model.Tab
import it.xonos.ma.phone.ui.Host
import it.xonos.ma.phone.ui.XonosScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Disegna le schermate dell'app del telefono in PNG, con Compose Desktop e dati finti: le stesse
 * composable dell'app, lo stesso PhoneModel, un Music Assistant finto ([FakeServer]).
 *
 * -Dxonos.out=cartella (predefinita out/), -Dxonos.lang=it|en|... (predefinita it),
 * -Dxonos.only=home,player (solo alcune scene).
 */
fun main() {
    val server = FakeServer(::answer)
    Catalog.colors.forEach { (id, colors) -> server.covers[id] = colors }
    System.setProperty("xonos.url", server.start())
    System.setProperty("xonos.token", "screens")
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory()) }.build()
    }
    val out = File(System.getProperty("xonos.out") ?: "out").apply { mkdirs() }
    val lang = System.getProperty("xonos.lang") ?: "it"
    val only = System.getProperty("xonos.only")?.split(',')?.toSet()
    for ((name, scene) in SCENES) {
        if (only != null && name !in only) continue
        shoot(File(out, "$name-$lang.png"), lang, scene)
        println("$name-$lang.png")
    }
    server.stop()
    exitProcess(0)
}

private const val WIDTH = 1080
private const val HEIGHT = 2340
private const val DENSITY = 2.625f

/** Una scena: prepara il modello (schede, pagine, lettore) e dice cosa sta suonando. */
private class Scene(val now: NowPlaying? = PLAYING, val configured: Boolean = true, val setUp: PhoneModel.() -> Unit)

private fun shoot(file: File, lang: String, scene: Scene) {
    Texts.language = lang
    val settings = Settings()
    if (!scene.configured) settings.save(Settings.Config("", "", ""))
    val repo = MaRepository(settings)
    repo.acquire()
    if (scene.configured) runBlocking { withTimeout(10_000) { repo.connection.first { it is Connection.Connected } } }
    val model = PhoneModel(repo, Library(repo), settings, { "xonos-screens" }, MutableStateFlow(true), CoroutineScope(SupervisorJob() + Dispatchers.Default))
    scene.setUp(model)
    val host = FakeHost(Language.of(if (lang == "zh") "zh-CN" else lang))
    val composeScene = ImageComposeScene(WIDTH, HEIGHT, Density(DENSITY)) { XonosScreen(model, FakeLink(scene.now), host) }
    // Il tempo di caricare le copertine e di finire le animazioni.
    val start = System.nanoTime()
    while (System.nanoTime() - start < SETTLE_NS) {
        Snapshot.sendApplyNotifications()
        composeScene.render(System.nanoTime() - start)
        Thread.sleep(40)
    }
    Snapshot.sendApplyNotifications()
    val image = composeScene.render(System.nanoTime() - start)
    file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    composeScene.close()
    repo.release()
}

private const val SETTLE_NS = 3_000_000_000L

/** Cosa risponde il Music Assistant finto: il brano che suona e il suo testo; il resto è vuoto. */
private fun answer(command: String, args: JSONObject): Any? = when (command) {
    "music/item_by_uri" -> {
        val uri = args.optString("uri")
        val id = uri.substringAfterLast('/')
        val album = id.substringBefore('-')
        val n = id.substringAfter('-').toIntOrNull()
        if (n != null && album in Catalog.ALBUMS) Catalog.trackJson(album, n) else null
    }
    "metadata/get_track_lyrics" -> JSONArray().put(JSONObject.NULL).put(Catalog.LYRICS)
    else -> null
}

private val EMOZIONI = Catalog.tracks("emozioni")

/** Suona il secondo brano di Emozioni, a 25 secondi. */
private val PLAYING = NowPlaying(
    title = EMOZIONI[1].name,
    artist = "Lucio Battisti",
    album = "Emozioni",
    artwork = null,
    uri = EMOZIONI[1].uri,
    mediaType = "track",
    albumUri = "library://album/emozioni",
    artistUri = "library://artist/battisti",
    durationMs = EMOZIONI[1].durationSec * 1000L,
    playing = true,
    buffering = false,
    canSeek = true,
    shuffle = false,
    repeat = Repeat.ALL,
    favorite = true,
    queue = EMOZIONI.map { QueueEntry(it.name, "Lucio Battisti", null) },
    queueIndex = 1,
)

/** Il lettore finto: fermo a 25 secondi; i comandi non fanno niente. */
private class FakeLink(now: NowPlaying?) : PlayerLink {
    override val nowPlaying = MutableStateFlow(now?.withArtwork())
    override val problem = MutableStateFlow<String?>(null)
    override fun positionMs() = 25_500L
    override fun playPause() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setShuffle(on: Boolean) = Unit
    override fun setRepeat(mode: Repeat) = Unit
    override fun setFavorite(on: Boolean) = Unit
    override fun skipTo(index: Int) = Unit

    /** Le copertine del lettore vengono dal server finto, come quelle del menu. */
    private fun NowPlaying.withArtwork(): NowPlaying {
        val base = System.getProperty("xonos.url")
        val art = "$base/imageproxy/emozioni?size=512&fmt=jpg"
        return copy(artwork = art, queue = queue.map { it.copy(artwork = "$base/imageproxy/emozioni?size=256&fmt=jpg") })
    }
}

private class FakeHost(override val language: Language) : Host {
    override fun describe(connection: Connection): String? = when (connection) {
        is Connection.Failed -> connection.message
        Connection.NotConfigured -> Texts.string(R.string.problem_not_configured)
        else -> null
    }

    override fun describe(error: Throwable): String = when ((error as? MaException)?.problem) {
        Problem.NO_ANSWER -> Texts.string(R.string.problem_no_answer_from).format((error as MaException).detail)
        else -> error.message ?: Texts.string(R.string.error_unknown)
    }

    override fun changeLanguage(language: Language) = Unit
    override val batteryRestricted = true
    override fun askBatteryExemption() = Unit
    override val version = "0.1.17"
}

private fun <T> ready(value: T) = Load.Ready(value)

private val SCENES = listOf(
    "home" to Scene {
        val home = page as HomePage
        home.recent = ready(
            listOf(
                Catalog.album("emozioni"),
                Catalog.playlist("viaggio", "Viaggio in auto", "Xonos"),
                Catalog.podcast("morning", "Morning", "Il Post"),
                Catalog.album("bollicine"),
                Catalog.radio("deejay", "Radio Deejay"),
            ),
        )
        home.rows = ready(
            listOf(
                HomePage.Row(Catalog.folder("added", "Recently added albums", null), ready(listOf("padrone", "creuza", "nero", "mare").map(Catalog::album))),
                HomePage.Row(Catalog.folder("artists", "Random artists", null), ready(listOf("battisti", "vasco", "mina", "dalla", "battiato").map { Catalog.artist(it) })),
                HomePage.Row(
                    Catalog.folder("playlists", "Favorite playlists", "Your playlists"),
                    ready(listOf(Catalog.playlist("domenica", "Domenica mattina", "Spotify"), Catalog.playlist("ottanta", "Anni Ottanta", "Spotify"), Catalog.playlist("viaggio", "Viaggio in auto", "Xonos"))),
                ),
            ),
        )
        home.started = true
    },
    "library" to Scene {
        select(Tab.LIBRARY)
        val library = page as LibraryPage
        library.shelves = listOf(Shelf.PLAYLISTS, Shelf.ALBUMS, Shelf.ARTISTS, Shelf.TRACKS, Shelf.FAVORITES, Shelf.PODCASTS, Shelf.RADIOS, Shelf.GENRES, Shelf.BROWSE)
        library.shelf = Shelf.ALBUMS
        library.started = true
        shelf(library, Shelf.ALBUMS).apply {
            items.addAll(Catalog.ALBUMS.keys.map(Catalog::album))
            end = true
        }
    },
    "album" to Scene {
        select(Tab.LIBRARY)
        push(ItemPage(Catalog.album("emozioni")).apply { tracks = ready(EMOZIONI); started = true })
    },
    "artist" to Scene {
        select(Tab.LIBRARY)
        push(
            ArtistPage(Catalog.artist("battisti")).apply {
                top = ready(listOf(EMOZIONI[0], EMOZIONI[2], Catalog.track("canto", 4), EMOZIONI[1], Catalog.track("anima", 3), EMOZIONI[3]))
                albums = ready(listOf("anima", "canto", "emozioni").map(Catalog::album))
                tracks = ready(EMOZIONI + Catalog.tracks("anima") + Catalog.tracks("canto"))
                started = true
            },
        )
    },
    "search" to Scene {
        select(Tab.SEARCH)
        val search = page as SearchPage
        search.query = "battisti"
        search.results = ready(
            SearchResults(
                "battisti",
                Catalog.artist("battisti"),
                mapOf(
                    "artists" to listOf(Catalog.artist("battisti")),
                    "tracks" to listOf(EMOZIONI[0], EMOZIONI[2], Catalog.track("canto", 4), EMOZIONI[1], EMOZIONI[3]),
                    "albums" to listOf("emozioni", "anima", "canto").map(Catalog::album),
                    "playlists" to listOf(Catalog.playlist("viaggio", "Battisti in auto", "Spotify")),
                ),
            ),
        )
        search.started = true
    },
    "player" to Scene {
        (page as HomePage).started = true
        playerOpen = true
    },
    "lyrics" to Scene {
        (page as HomePage).started = true
        playerOpen = true
        playerPanel = PlayerPanel.LYRICS
    },
    "queue" to Scene {
        (page as HomePage).started = true
        playerOpen = true
        playerPanel = PlayerPanel.QUEUE
    },
    "podcast" to Scene {
        select(Tab.LIBRARY)
        push(
            ItemPage(Catalog.podcast("morning", "Morning", "Il Post")).apply {
                tracks = ready(
                    listOf(
                        Catalog.episode("morning", 5, "Morning – 25 ottobre", 28),
                        Catalog.episode("morning", 4, "Morning – 24 ottobre", 31, resumeMinutes = 12),
                        Catalog.episode("morning", 3, "Morning – 23 ottobre", 27, played = true),
                        Catalog.episode("morning", 2, "Morning – 22 ottobre", 30, played = true),
                    ),
                )
                started = true
            },
        )
    },
    "settings" to Scene {
        (page as HomePage).started = true
        val settings = settingsPage().apply {
            results = listOf(
                ConnectionCheck.Result.Ok(ConnectionCheck.Place.HOME, "2.10.8", 12, ConnectionCheck.Audio.Ready),
                ConnectionCheck.Result.Failed(ConnectionCheck.Place.AWAY, MaException("x", problem = Problem.NO_ANSWER, detail = "https://musica.example.com")),
            )
            tried = true
        }
        push(settings)
    },
    "welcome" to Scene(now = null, configured = false) {},
)
