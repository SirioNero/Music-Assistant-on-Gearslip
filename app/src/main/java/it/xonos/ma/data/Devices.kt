package it.xonos.ma.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Un lettore di Music Assistant su cui il telefono può far suonare la musica: una cassa, una TV,
 * un gruppo di casse. Quelli delle app (gli altri telefoni, i browser) Music Assistant li segna
 * "privati" e non si offrono.
 */
data class Device(
    val id: String,
    val name: String,
    /** "player", "stereo_pair" o "group". */
    val type: String,
    /** "playing", "paused" o "idle". */
    val state: String,
    /** Da 0 a 100; null se il lettore non lo dice. */
    val volume: Int?,
    val available: Boolean,
) {
    val playing: Boolean get() = state == "playing"

    companion object {
        fun from(o: JSONObject?): Device? {
            if (o == null) return null
            return Device(
                id = o.str("player_id") ?: return null,
                name = o.str("display_name") ?: o.str("name") ?: o.str("player_id")!!,
                type = o.str("type") ?: "player",
                state = o.str("playback_state") ?: o.str("state") ?: "idle",
                volume = if (o.isNull("volume_level")) null else o.optInt("volume_level"),
                available = o.optBoolean("available", true),
            )
        }
    }
}

/**
 * I lettori di Music Assistant e il passaggio della coda dall'uno all'altro (player_queues/transfer:
 * la coda e il punto del brano passano al lettore nuovo, quella di prima si svuota).
 */
class Devices(private val repo: MaRepository) {

    /** I lettori su cui si può far suonare, tranne il telefono ([phoneId]): prima quelli che suonano. */
    suspend fun targets(phoneId: String): List<Device> =
        (repo.command("players/all") as? JSONArray).objects()
            .filter { target(it, phoneId) }
            .mapNotNull(Device::from)
            .sortedWith(compareBy({ !it.playing }, { it.name.lowercase() }))

    private fun target(o: JSONObject, phoneId: String): Boolean =
        o.str("player_id") != phoneId &&
            o.optBoolean("available", true) &&
            o.optBoolean("enabled", true) &&
            !o.optBoolean("hide_in_ui") &&
            !o.optBoolean("private") &&
            // Una cassa legata a un'altra suona con lei: si sceglie quella che guida.
            o.isNull("synced_to") &&
            (o.str("type") ?: "player") in TARGET_TYPES

    /** Sposta la coda da [from] a [to]: riparte dallo stesso punto se suonava. */
    suspend fun transfer(from: String, to: String) {
        repo.command("player_queues/transfer", 60_000) {
            put("source_queue_id", from)
            put("target_queue_id", to)
        }
    }

    /** Lo stato della coda di un lettore; null se Music Assistant non la conosce. */
    suspend fun queue(queueId: String): QueueState? =
        QueueState.from(repo.command("player_queues/get") { put("queue_id", queueId) } as? JSONObject)

    /** Un tratto della coda, da [offset]. */
    suspend fun items(queueId: String, offset: Int, limit: Int): List<QueueItem> =
        (
            repo.command("player_queues/items") {
                put("queue_id", queueId)
                put("offset", offset)
                put("limit", limit)
            } as? JSONArray
            ).objects().mapNotNull(QueueItem::from)

    /** Un comando alla coda (play_pause, next, previous, seek, shuffle, repeat, play_index...). */
    suspend fun command(name: String, queueId: String, fill: JSONObject.() -> Unit = {}) {
        repo.command("player_queues/$name") {
            put("queue_id", queueId)
            fill()
        }
    }

    suspend fun setVolume(playerId: String, level: Int) {
        repo.command("players/cmd/volume_set") {
            put("player_id", playerId)
            put("volume_level", level.coerceIn(0, 100))
        }
    }

    companion object {
        /** I tipi di lettore che suonano davvero (come PLAYBACK_TARGET_TYPES in Music Assistant). */
        val TARGET_TYPES = setOf("player", "stereo_pair", "group")
    }
}
