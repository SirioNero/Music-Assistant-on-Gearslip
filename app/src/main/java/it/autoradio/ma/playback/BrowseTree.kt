package it.autoradio.ma.playback

import android.util.Log
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import it.autoradio.ma.data.Library
import it.autoradio.ma.data.MaItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Il menu musicale che Gearslip mostra: le voci della libreria di Music Assistant, e cosa
 * succede quando se ne sceglie una.
 *
 * Gli identificativi dicono già cosa fare:
 * - `root`, `cat|<voce>`, `rec|<provider>|<id>`, `dir|<percorso>` sono cartelle;
 * - `open|<uri>` è un album, una playlist, un artista o un podcast: si apre, o si suona tutto;
 * - `play|<uri>` si suona da solo;
 * - `in|<album o playlist>|<brano>` suona l'album o la playlist a partire da quel brano;
 * - `list|<cartella>|<n>` suona i brani di una cartella a partire dall'n-esimo.
 */
class BrowseTree(
    private val library: Library,
    private val queueId: () -> String,
    private val artwork: Artwork,
) {

    private enum class Category(val id: String, val title: String, val icon: String, val kind: String?, val favoriteOnly: Boolean = false) {
        RECENT("recent", "Ascoltati di recente", "recent", null),
        FOR_YOU("foryou", "Per te", "foryou", null),
        PLAYLISTS("playlists", "Playlist", "playlist", "playlists"),
        ALBUMS("albums", "Album", "album", "albums"),
        ARTISTS("artists", "Artisti", "artist", "artists"),
        FAVORITES("favorites", "Brani preferiti", "favorite", "tracks", favoriteOnly = true),
        RADIOS("radios", "Radio", "radio", "radios"),
        PODCASTS("podcasts", "Podcast", "podcast", "podcasts"),
        AUDIOBOOKS("audiobooks", "Audiolibri", "audiobook", "audiobooks"),
        BROWSE("browse", "Sfoglia i servizi", "browse", null),
    }

    /** Gli elenchi già chiesti, per suonare "da qui in poi" senza richiederli. */
    private val lists = LruCache<String, List<MaItem>>(48)

    /** Le voci già mostrate: servono a Media3 per un elemento singolo e all'auto per vedere subito titolo e copertina. */
    private val known = LruCache<String, MediaItem>(1_000)

    /** Le righe di "Per te", con il loro contenuto se il server l'ha già mandato. */
    private val rows = LruCache<String, MaItem>(64)

    fun root(): MediaItem = folder(ROOT, "Autoradio", null, "track")

    fun item(mediaId: String): MediaItem? = if (mediaId == ROOT) root() else known.get(mediaId)

    /** Riempie titolo e copertina di un elemento chiesto dall'auto, se lo si conosce. */
    fun resolve(item: MediaItem): MediaItem {
        val cached = known.get(item.mediaId) ?: return item
        return item.buildUpon().setMediaMetadata(cached.mediaMetadata).build()
    }

    suspend fun children(parentId: String): List<MediaItem> {
        val children = when {
            parentId == ROOT -> rootChildren()
            parentId.startsWith("cat|") -> category(parentId)
            parentId.startsWith("rec|") -> entries(parentId, recommendation(parentId))
            parentId.startsWith("dir|") -> entries(parentId, library.browse(parentId.removePrefix("dir|")))
            parentId.startsWith("open|") -> open(parentId)
            else -> emptyList()
        }
        children.forEach { known.put(it.mediaId, it) }
        return children
    }

    suspend fun search(query: String): List<MediaItem> {
        val id = "search|$query"
        return entries(id, library.search(query)).onEach { known.put(it.mediaId, it) }
    }

    // ---------- Riproduzione ----------

    /** Suona ciò che l'auto ha scelto: un elemento del menu, una ricerca a voce o la ripresa. */
    suspend fun play(item: MediaItem) {
        val queue = queueId()
        val query = item.requestMetadata.searchQuery
        val id = item.mediaId
        when {
            id == RESUME -> library.resume(queue)
            !query.isNullOrBlank() && id.isEmpty() -> playSearch(queue, query)
            id.startsWith("play|") -> library.playMedia(queue, listOf(id.removePrefix("play|")))
            id.startsWith("open|") -> library.playMedia(queue, listOf(id.removePrefix("open|")))
            id.startsWith("in|") -> {
                val (context, track) = id.removePrefix("in|").split('|', limit = 2).let { it[0] to it.getOrNull(1) }
                library.playMedia(queue, listOf(context), startItem = track)
            }
            id.startsWith("list|") -> {
                val rest = id.removePrefix("list|")
                val parent = rest.substringBeforeLast('|')
                val start = rest.substringAfterLast('|').toIntOrNull() ?: 0
                val items = lists.get(parent) ?: run {
                    children(parent)
                    lists.get(parent)
                } ?: return
                val uris = items.drop(start).filter { it.mediaType in TRACK_LIKE }.take(MAX_QUEUE).map { it.uri }
                if (uris.isNotEmpty()) library.playMedia(queue, uris)
            }
            else -> Log.w(TAG, "Non so suonare $id")
        }
    }

    /** "Metti ..." detto all'auto: si suona il risultato che corrisponde meglio. */
    private suspend fun playSearch(queue: String, query: String) {
        val results = library.search(query)
        val best = results.firstOrNull { it.name.equals(query.trim(), ignoreCase = true) && it.playable }
            ?: results.firstOrNull { it.playable }
            ?: return
        library.playMedia(queue, listOf(best.uri))
    }

    // ---------- Cartelle ----------

    private suspend fun rootChildren(): List<MediaItem> = coroutineScope {
        // Le voci vuote non servono in auto: si chiede quanti elementi ci sono, tutti insieme.
        val counts = Category.entries.associateWith { category ->
            category.kind?.let { kind -> async { runCatching { library.count(kind, category.favoriteOnly) }.getOrDefault(-1) } }
        }
        Category.entries.mapNotNull { category ->
            val count = counts[category]?.await() ?: -1
            if (count == 0) null else folder("cat|${category.id}", category.title, null, category.icon)
        }
    }

    private suspend fun category(id: String): List<MediaItem> {
        val category = Category.entries.firstOrNull { "cat|${it.id}" == id } ?: return emptyList()
        val items = when (category) {
            Category.RECENT -> library.recent()
            Category.FOR_YOU -> library.recommendations().onEach { rows.put("rec|${it.provider}|${it.itemId}", it) }
            Category.FAVORITES -> library.libraryItems("tracks", favorite = true, orderBy = "timestamp_added_desc")
            Category.BROWSE -> library.browse(null)
            else -> library.libraryItems(category.kind ?: return emptyList())
        }
        if (category == Category.FOR_YOU) {
            return items.filter { it.mediaType == "folder" }.map { row ->
                folder("rec|${row.provider}|${row.itemId}", row.name, row.subtitle, "foryou")
            }
        }
        return entries(id, items)
    }

    private suspend fun recommendation(id: String): List<MaItem> {
        val row = rows.get(id) ?: run {
            val (provider, itemId) = id.removePrefix("rec|").split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            MaItem("", "folder", itemId, provider, "", null, null, false, false, 0, null)
        }
        return library.recommendationItems(row)
    }

    private suspend fun open(id: String): List<MediaItem> {
        val uri = id.removePrefix("open|")
        val (provider, type, itemId) = parseUri(uri) ?: return emptyList()
        val item = MaItem(uri, type, itemId, provider, "", null, null, true, false, 0, null)
        val items = when (type) {
            "album" -> library.albumTracks(item)
            "playlist" -> library.playlistTracks(item)
            "artist" -> library.artistAlbums(item) + library.artistTracks(item).take(MAX_ARTIST_TRACKS)
            "podcast" -> library.podcastEpisodes(item)
            else -> emptyList()
        }
        // Dentro un album o una playlist un brano fa partire tutto da lì: avanti e indietro restano nell'elenco.
        val contextual = type == "album" || type == "playlist"
        return entries(id, items, contextUri = uri.takeIf { contextual })
    }

    private fun entries(parentId: String, items: List<MaItem>, contextUri: String? = null): List<MediaItem> {
        lists.put(parentId, items)
        return items.mapIndexedNotNull { index, item ->
            when {
                item.mediaType == "folder" -> {
                    val path = item.path ?: return@mapIndexedNotNull null
                    folder("dir|$path", item.name, item.subtitle, "browse", item)
                }
                item.browsable -> entry(item, "open|${item.uri}", browsable = true, playable = item.playable)
                !item.playable -> null
                item.mediaType == "track" && contextUri != null -> entry(item, "in|$contextUri|${item.uri}")
                item.mediaType == "track" && parentId.startsWith("cat|") -> entry(item, "list|$parentId|$index")
                item.mediaType == "track" && parentId.startsWith("open|") -> entry(item, "list|$parentId|$index")
                else -> entry(item, "play|${item.uri}")
            }
        }.take(Library.LIST_LIMIT)
    }

    private fun entry(item: MaItem, mediaId: String, browsable: Boolean = false, playable: Boolean = true): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setDisplayTitle(item.name)
            .setSubtitle(item.subtitle)
            .setArtworkUri(artwork.uriFor(item.image, LIST_ART) ?: artwork.iconUri(iconFor(item.mediaType)))
            .setIsBrowsable(browsable)
            .setIsPlayable(playable)
            .setMediaType(mediaTypeOf(item.mediaType))
            .build()
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
    }

    private fun folder(mediaId: String, title: String, subtitle: String?, icon: String, item: MaItem? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artwork.uriFor(item?.image, LIST_ART) ?: artwork.iconUri(icon))
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(metadata).build()
    }

    companion object {
        private const val TAG = "Autoradio"
        const val ROOT = "root"

        /** La ripresa della coda quando l'auto preme play e il telefono non ha ancora niente in mano. */
        const val RESUME = "resume"

        private const val LIST_ART = 256
        private const val MAX_QUEUE = 100
        private const val MAX_ARTIST_TRACKS = 100
        private val TRACK_LIKE = setOf("track", "podcast_episode", "audiobook")

        /** "spotify://album/123" diventa provider, tipo e id. */
        fun parseUri(uri: String): Triple<String, String, String>? {
            val provider = uri.substringBefore("://", "").ifEmpty { return null }
            val rest = uri.substringAfter("://")
            val type = rest.substringBefore('/', "").ifEmpty { return null }
            return Triple(provider, type, rest.substringAfter('/'))
        }

        private fun iconFor(type: String) = when (type) {
            "album" -> "album"
            "artist" -> "artist"
            "playlist" -> "playlist"
            "radio" -> "radio"
            "podcast", "podcast_episode" -> "podcast"
            "audiobook" -> "audiobook"
            "folder" -> "browse"
            else -> "track"
        }

        private fun mediaTypeOf(type: String) = when (type) {
            "track" -> MediaMetadata.MEDIA_TYPE_MUSIC
            "album" -> MediaMetadata.MEDIA_TYPE_ALBUM
            "artist" -> MediaMetadata.MEDIA_TYPE_ARTIST
            "playlist" -> MediaMetadata.MEDIA_TYPE_PLAYLIST
            "radio" -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
            "podcast" -> MediaMetadata.MEDIA_TYPE_PODCAST
            "podcast_episode" -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
            "audiobook" -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
            else -> MediaMetadata.MEDIA_TYPE_MIXED
        }
    }
}
