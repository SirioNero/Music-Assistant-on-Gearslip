package it.xonos.ma.data

import org.json.JSONArray
import org.json.JSONObject

/** Lo stato del collegamento con Music Assistant. */
sealed interface Connection {
    data object NotConfigured : Connection
    data class Connecting(val url: String) : Connection
    data class Connected(val url: String, val server: ServerInfo) : Connection
    data class Failed(val message: String) : Connection
}

/** Cosa dice di sé il server appena collegato. */
data class ServerInfo(
    val name: String,
    val version: String,
    val schemaVersion: Int,
    /**
     * L'utente vede solo alcuni lettori: il nostro gli è permesso solo se la connessione audio
     * si apre dopo quella dell'API, quindi va riaperta a ogni riconnessione dell'API.
     */
    val limitedPlayers: Boolean = false,
) {
    companion object {
        fun from(info: JSONObject): ServerInfo? {
            if (info.str("server_id") == null) return null
            return ServerInfo(
                name = info.str("name") ?: "Music Assistant",
                version = info.str("server_version") ?: "?",
                schemaVersion = info.optInt("schema_version"),
            )
        }
    }
}

/** Un'immagine come la descrive Music Assistant. */
data class MaImage(val path: String, val provider: String, val proxyId: String?, val remote: Boolean) {
    companion object {
        fun from(o: JSONObject?): MaImage? {
            if (o == null) return null
            val path = o.str("path") ?: return null
            return MaImage(path, o.str("provider").orEmpty(), o.str("proxy_id"), o.optBoolean("remotely_accessible"))
        }

        /** L'immagine di un elemento: la sua, quella dei metadati (la copertina, se c'è), o quella dell'album. */
        fun of(item: JSONObject?): MaImage? {
            if (item == null) return null
            from(item.optJSONObject("image"))?.let { return it }
            val images = item.optJSONObject("metadata")?.optJSONArray("images")
            var first: MaImage? = null
            for (o in images.objects()) {
                val image = from(o) ?: continue
                if (o.str("type") == "thumb") return image
                if (first == null) first = image
            }
            return first ?: item.optJSONObject("album")?.let { from(it.optJSONObject("image")) }
        }
    }
}

/** Un elemento di Music Assistant da mostrare nel menu: brano, album, artista, playlist, radio, cartella... */
data class MaItem(
    val uri: String,
    val mediaType: String,
    val itemId: String,
    val provider: String,
    val name: String,
    val subtitle: String?,
    val image: MaImage?,
    val playable: Boolean,
    val favorite: Boolean,
    val durationSec: Int,
    /** Solo le cartelle di "Sfoglia": il percorso da chiedere a music/browse. */
    val path: String?,
    /** Le righe di "Per te" che le versioni vecchie mandano già piene. */
    val inline: List<MaItem> = emptyList(),
) {
    /** Cosa si apre come elenco; il resto si suona e basta. */
    val browsable: Boolean get() = mediaType in BROWSABLE

    companion object {
        private val BROWSABLE = setOf("album", "playlist", "artist", "podcast", "folder")

        fun from(o: JSONObject): MaItem? {
            val uri = o.str("uri") ?: return null
            val type = o.str("media_type") ?: return null
            val name = o.str("name") ?: return null
            val items = o.optJSONArray("items")
            return MaItem(
                uri = uri,
                mediaType = type,
                itemId = o.str("item_id").orEmpty(),
                provider = o.str("provider").orEmpty(),
                name = name,
                subtitle = subtitleOf(o, type),
                image = MaImage.of(o),
                playable = if (o.has("is_playable")) o.optBoolean("is_playable") else type != "folder",
                favorite = o.optBoolean("favorite"),
                durationSec = o.optInt("duration"),
                path = o.str("path"),
                inline = items.objects().mapNotNull(::from),
            )
        }

        private fun subtitleOf(o: JSONObject, type: String): String? = when (type) {
            "track" -> listOfNotNull(artistsOf(o), o.optJSONObject("album")?.str("name")).joinToString(" · ")
            "album" -> listOfNotNull(artistsOf(o), o.optInt("year").takeIf { it > 0 }?.toString()).joinToString(" · ")
            "playlist" -> o.str("owner")
            "podcast_episode" -> o.optJSONObject("podcast")?.str("name")
            "audiobook" -> o.optJSONArray("authors")?.let { authors ->
                (0 until authors.length()).mapNotNull { authors.optString(it).takeIf(String::isNotEmpty) }.joinToString(", ")
            }
            "folder" -> o.str("subtitle")
            else -> null
        }?.ifEmpty { null }
    }
}

/** Un elemento della coda di Music Assistant. */
data class QueueItem(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSec: Int,
    val image: MaImage?,
    val uri: String?,
    val mediaType: String?,
    val favorite: Boolean,
    val itemId: String?,
    val provider: String?,
) {
    companion object {
        fun from(o: JSONObject?): QueueItem? {
            if (o == null) return null
            val id = o.str("queue_item_id") ?: return null
            val media = o.optJSONObject("media_item")
            return QueueItem(
                id = id,
                title = media?.str("name") ?: o.str("name").orEmpty(),
                artist = media?.let { artistsOf(it) ?: it.optJSONObject("podcast")?.str("name") },
                album = media?.optJSONObject("album")?.str("name"),
                durationSec = o.optInt("duration").takeIf { it > 0 } ?: media?.optInt("duration") ?: 0,
                image = MaImage.from(o.optJSONObject("image")) ?: MaImage.of(media),
                uri = media?.str("uri"),
                mediaType = media?.str("media_type"),
                favorite = media?.optBoolean("favorite") ?: false,
                itemId = media?.str("item_id"),
                provider = media?.str("provider"),
            )
        }
    }
}

/** Lo stato della coda del nostro lettore in Music Assistant. */
data class QueueState(
    /** "playing", "paused" o "idle": per questo lettore la pausa è un "idle" con la posizione ricordata. */
    val state: String,
    val items: Int,
    val currentIndex: Int?,
    val elapsedSec: Double,
    val shuffle: Boolean,
    /** "off", "one" o "all". */
    val repeat: String,
    val current: QueueItem?,
    /** Per le radio: il titolo che la stazione sta trasmettendo. */
    val streamTitle: String?,
) {
    val playing: Boolean get() = state == "playing"

    companion object {
        fun from(o: JSONObject?): QueueState? {
            if (o == null || o.str("queue_id") == null) return null
            val current = o.optJSONObject("current_item")
            return QueueState(
                state = o.str("state") ?: "idle",
                items = o.optInt("items"),
                currentIndex = if (o.isNull("current_index")) null else o.optInt("current_index"),
                elapsedSec = o.optDouble("elapsed_time", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                shuffle = o.optBoolean("shuffle_enabled"),
                repeat = o.str("repeat_mode") ?: "off",
                current = QueueItem.from(current),
                streamTitle = current?.optJSONObject("streamdetails")?.str("stream_title"),
            )
        }
    }
}

/** Gli artisti di un brano o di un album, separati da virgole. */
internal fun artistsOf(o: JSONObject): String? {
    val names = o.optJSONArray("artists").objects().mapNotNull { it.str("name") }
    return names.joinToString(", ").ifEmpty { null }
}

/** Come optString, ma restituisce null per valori assenti, null o vuoti (org.json restituirebbe "null"). */
fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

/** Gli oggetti di un array JSON, saltando ciò che non è un oggetto; vuoto se l'array manca. */
fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
