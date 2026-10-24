package it.xonos.ma.data

import org.json.JSONArray
import org.json.JSONObject

/** Le domande che il menu dell'auto fa alla libreria di Music Assistant, e i comandi della coda. */
class Library(private val repo: MaRepository) {

    private suspend fun items(command: String, timeoutMs: Long = 30_000, fill: JSONObject.() -> Unit = {}): List<MaItem> {
        val result = repo.command(command, timeoutMs, fill) as? JSONArray ?: return emptyList()
        return result.objects().mapNotNull(MaItem::from)
    }

    /** Gli ultimi ascolti, anche quelli interrotti a metà. */
    suspend fun recent(limit: Int = 40) = items("music/recently_played_items") {
        put("limit", limit)
        put("fully_played_only", false)
    }

    /** Gli elementi della libreria di un tipo ("albums", "artists", "playlists", "tracks", "radios", ...). */
    suspend fun libraryItems(
        kind: String,
        favorite: Boolean? = null,
        orderBy: String = "sort_name",
        limit: Int = LIST_LIMIT,
    ) = items("music/$kind/library_items") {
        if (favorite != null) put("favorite", favorite)
        put("order_by", orderBy)
        put("limit", limit)
    }

    /** Quanti elementi di un tipo ci sono in libreria; -1 se il server non lo sa dire. */
    suspend fun count(kind: String, favoriteOnly: Boolean = false): Int =
        (repo.command("music/$kind/count") { put("favorite_only", favoriteOnly) } as? Number)?.toInt() ?: -1

    suspend fun albumTracks(album: MaItem) = items("music/albums/album_tracks") { identify(album) }

    /** Le playlist lunghe arrivano a pezzi e un servizio come Spotify può metterci un po'. */
    suspend fun playlistTracks(playlist: MaItem) = items("music/playlists/playlist_tracks", 90_000) { identify(playlist) }

    suspend fun artistAlbums(artist: MaItem) = items("music/artists/artist_albums") { identify(artist) }

    suspend fun artistTracks(artist: MaItem) = items("music/artists/artist_tracks") { identify(artist) }

    suspend fun podcastEpisodes(podcast: MaItem) = items("music/podcasts/podcast_episodes", 60_000) { identify(podcast) }

    /** Le righe di "Per te" (consigli della libreria e dei servizi), senza il loro contenuto. */
    suspend fun recommendations() = items("music/recommendations", 60_000)

    /** Il contenuto di una riga di "Per te". */
    suspend fun recommendationItems(row: MaItem): List<MaItem> {
        if (row.inline.isNotEmpty()) return row.inline
        return items("music/recommendations/items", 60_000) {
            put("provider", row.provider)
            put("item_id", row.itemId)
        }
    }

    /** "Sfoglia": i servizi e le loro cartelle, senza la voce per risalire. */
    suspend fun browse(path: String?) = items("music/browse", 60_000) {
        if (path != null) put("path", path)
    }.filter { it.name != ".." }

    /** Cerca dappertutto: prima gli artisti, poi brani, album, playlist e il resto. */
    suspend fun search(query: String, limit: Int = 8): List<MaItem> {
        val result = repo.command("music/search", 45_000) {
            put("search_query", query)
            put("limit", limit)
        } as? JSONObject ?: return emptyList()
        return SEARCH_ORDER.flatMap { key -> result.optJSONArray(key).objects().mapNotNull(MaItem::from) }
    }

    suspend fun item(uri: String): MaItem? =
        (repo.command("music/item_by_uri") { put("uri", uri) } as? JSONObject)?.let(MaItem::from)

    /** Suona sulla coda [queueId]: uno o più indirizzi, eventualmente partendo da [startItem]. */
    suspend fun playMedia(queueId: String, media: List<String>, startItem: String? = null, option: String = "replace") {
        repo.command("player_queues/play_media", 60_000) {
            put("queue_id", queueId)
            put("media", if (media.size == 1) media[0] else JSONArray(media))
            put("option", option)
            if (startItem != null) put("start_item", startItem)
        }
    }

    /** Riprende la coda da dove si era fermata (play su una coda ferma la fa ripartire). */
    suspend fun resume(queueId: String) {
        repo.command("player_queues/play") { put("queue_id", queueId) }
    }

    /** Aggiunge o toglie dai preferiti; per toglierlo serve l'elemento della libreria. */
    suspend fun setFavorite(uri: String, mediaType: String, provider: String?, itemId: String?, favorite: Boolean) {
        if (favorite) {
            repo.command("music/favorites/add_item") { put("item", uri) }
            return
        }
        val libraryId = if (provider == "library") itemId else item(uri)?.takeIf { it.provider == "library" }?.itemId
        if (libraryId.isNullOrEmpty()) throw MaException("Non è nella libreria")
        repo.command("music/favorites/remove_item") {
            put("media_type", mediaType)
            put("library_item_id", libraryId)
        }
    }

    private fun JSONObject.identify(item: MaItem) {
        put("item_id", item.itemId)
        put("provider_instance_id_or_domain", item.provider)
    }

    companion object {
        /** Più di così un elenco in auto non si scorre: e si risparmia memoria e dati. */
        const val LIST_LIMIT = 500

        private val SEARCH_ORDER = listOf("artists", "tracks", "albums", "playlists", "radio", "podcasts", "audiobooks")
    }
}
