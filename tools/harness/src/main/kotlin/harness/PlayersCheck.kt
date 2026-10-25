package harness

import it.xonos.ma.data.Connection
import it.xonos.ma.data.Devices
import it.xonos.ma.data.Library
import it.xonos.ma.data.MaRepository
import it.xonos.ma.data.QueueState
import it.xonos.ma.data.ScreenCache
import it.xonos.ma.data.Settings
import it.xonos.ma.phone.model.FixedFinder
import it.xonos.ma.phone.model.Load
import it.xonos.ma.phone.model.Notice
import it.xonos.ma.phone.model.PhoneModel
import it.xonos.ma.phone.model.value
import it.xonos.ma.sendspin.AudioEngine
import it.xonos.ma.sendspin.PcmDecoder
import it.xonos.ma.sendspin.SendspinClient
import it.xonos.ma.sendspin.StreamFormat
import it.xonos.ma.sendspin.TimeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Suona su" dal [PhoneModel] contro Music Assistant: il telefono e una cassa finta sono due
 * lettori Sendspin (la cassa con un altro product_name, così MA la tratta come un dispositivo e
 * la mostra a tutti, attraverso un Universal Player). La musica passa dal telefono alla cassa e
 * ritorna, dallo stesso punto; dalla cassa si comanda tutto; "suona" va al lettore scelto.
 */
fun main() {
    try {
        checks()
    } catch (e: Throwable) {
        e.printStackTrace()
        kotlin.system.exitProcess(2)
    }
}

private fun checks(): Unit = runBlocking {
    val url = System.getenv("MA_URL") ?: "http://127.0.0.1:8095"
    val token = System.getenv("MA_TOKEN") ?: error("MA_TOKEN")
    val settings = Settings()
    val repo = MaRepository(settings)
    val library = Library(repo)
    val devices = Devices(repo)
    repo.acquire()
    withTimeout(15_000) { repo.connection.first { it is Connection.Connected } }
    var failures = 0
    fun check(label: String, ok: Boolean, detail: String = "") {
        if (!ok) failures++
        println("${if (ok) "OK " else "NO "} $label${if (detail.isNotEmpty()) " · $detail" else ""}")
    }
    suspend fun until(ms: Long = 20_000, ok: suspend () -> Boolean): Boolean = withTimeoutOrNull(ms) {
        while (!ok()) delay(200)
        true
    } ?: false

    class Speaker(val id: String, name: String, product: String) {
        val outputs = mutableListOf<FakeOutput>()
        val audio: Long get() = outputs.sumOf { it.audioFrames.get() }
        val engine = AudioEngine({ r, c -> FakeOutput(r, c).also { outputs += it } }, { f -> PcmDecoder(f.channels, f.bitDepth) }, TimeFilter())
        val identity = SendspinClient.Identity(id, name, "JVM", "test", listOf(StreamFormat("pcm", 48000, 2, 16)), 800, 300, 50, false, product)
        val client = SendspinClient(repo.http, engine, { identity }) { _, _ -> }.also { it.start(url, token) }
    }
    val phone = Speaker("xonos-players-phone", "Telefono di prova", "Mobile Application")
    val room = Speaker("xonos-players-room", "Cassa del salotto", "Xonos test speaker")
    withTimeout(20_000) { phone.client.phase.first { it == SendspinClient.Phase.READY } }
    withTimeout(20_000) { room.client.phase.first { it == SendspinClient.Phase.READY } }
    val ready = MutableStateFlow(true)
    val model = PhoneModel(
        repo,
        library,
        settings,
        ScreenCache(null),
        FixedFinder(),
        "Xonos (prova)",
        { phone.id },
        ready,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
    suspend fun queue(id: String): QueueState? = devices.queue(id)

    // La cassa compare come Universal Player qualche secondo dopo (la prima volta 15 s).
    model.openDevices()
    val picker = model.picker!!
    until(60_000) {
        if (picker.devices !is Load.Loading && picker.devices.value?.none { it.name == "Cassa del salotto" } != false) {
            delay(2_000)
            model.loadDevices(picker)
        }
        picker.devices.value?.any { it.name == "Cassa del salotto" } == true
    }
    val targets = picker.devices.value.orEmpty()
    val speaker = targets.firstOrNull { it.name == "Cassa del salotto" }
    check("Suona su: la cassa c'è, il telefono no", speaker != null && targets.none { it.id == phone.id }, targets.joinToString { "${it.name} (${it.id}, ${it.type})" })
    speaker ?: error("Cassa non trovata")

    // Sul telefono suona un album.
    val album = library.search("Emozioni").values.flatten().first { it.mediaType == "album" && it.name == "Emozioni" }
    library.playMedia(phone.id, listOf(album.uri))
    until { queue(phone.id)?.playing == true && phone.audio > 48_000 }
    delay(3_000)
    val before = queue(phone.id)!!
    check("Il telefono suona", before.playing && phone.audio > 0, "${before.current?.title} a ${before.elapsedSec.toInt()} s, ${phone.audio} campioni")

    model.playOn(speaker)
    until { model.remote != null }
    check("Passata alla cassa: la scelta si chiude, avviso", model.remote?.device?.id == speaker.id && model.picker == null && model.notice == Notice.Moved(speaker.name), "${model.notice}")
    val roomAudio = room.audio
    until { queue(speaker.id)?.playing == true && room.audio > roomAudio + 48_000 }
    val moved = queue(speaker.id)
    val left = queue(phone.id)
    check(
        "La cassa suona dallo stesso brano e punto",
        moved?.playing == true && moved.current?.title == before.current?.title && moved.elapsedSec >= before.elapsedSec - 1,
        "${moved?.current?.title} a ${moved?.elapsedSec?.toInt()} s (prima ${before.elapsedSec.toInt()} s), audio cassa ${room.audio - roomAudio}",
    )
    check("La coda del telefono è vuota", (left?.items ?: 0) == 0, "${left?.items} brani")

    val remote = model.remote!!
    until { remote.nowPlaying.value?.title == moved?.current?.title && remote.nowPlaying.value?.queue?.isNotEmpty() == true }
    val shown = remote.nowPlaying.value
    check("Lo schermo mostra la cassa", shown?.title == moved?.current?.title && shown?.playing == true && remote.positionMs() > 0, "${shown?.title} ${remote.positionMs()} ms, coda ${shown?.queue?.size}")

    val index = queue(speaker.id)?.currentIndex
    remote.next()
    until { queue(speaker.id)?.currentIndex == (index ?: 0) + 1 }
    until { remote.nowPlaying.value?.title == queue(speaker.id)?.current?.title }
    check("Avanti sulla cassa", queue(speaker.id)?.currentIndex == (index ?: 0) + 1 && remote.nowPlaying.value?.title == queue(speaker.id)?.current?.title, "${remote.nowPlaying.value?.title}")
    remote.playPause()
    until { queue(speaker.id)?.playing == false }
    check("Pausa sulla cassa", queue(speaker.id)?.playing == false && until(5_000) { remote.nowPlaying.value?.playing == false })
    remote.playPause()
    until { queue(speaker.id)?.playing == true }
    check("Di nuovo play", queue(speaker.id)?.playing == true)
    remote.setVolume(30)
    until { devices.targets(phone.id).firstOrNull { it.id == speaker.id }?.volume == 30 }
    check("Volume della cassa", devices.targets(phone.id).firstOrNull { it.id == speaker.id }?.volume == 30 && remote.volume.value == 30)

    // Con la cassa scelta, "suona" va alla cassa.
    val track = library.albumTracks(album)[2]
    model.play(track)
    until { queue(speaker.id)?.current?.title == track.name }
    check("Suona va alla cassa scelta", queue(speaker.id)?.current?.title == track.name && (queue(phone.id)?.items ?: 0) == 0, "${queue(speaker.id)?.current?.title}")

    // Di nuovo sul telefono.
    delay(2_000)
    val atRoom = queue(speaker.id)!!
    val phoneAudio = phone.audio
    model.openDevices()
    model.playOn(null)
    until { model.remote == null }
    until { queue(phone.id)?.playing == true && phone.audio > phoneAudio + 48_000 }
    val back = queue(phone.id)
    check(
        "Tornata sul telefono, dallo stesso punto",
        model.remote == null && back?.playing == true && back.current?.title == atRoom.current?.title && back.elapsedSec >= atRoom.elapsedSec - 1,
        "${back?.current?.title} a ${back?.elapsedSec?.toInt()} s (cassa ${atRoom.elapsedSec.toInt()} s)",
    )
    check("La cassa si è fermata e svuotata", (queue(speaker.id)?.items ?: 0) == 0 && queue(speaker.id)?.playing != true)
    check("Avviso: di nuovo sul telefono", model.notice == Notice.Moved(null), "${model.notice}")

    // Se il telefono comincia a suonare per conto suo (l'auto), lo schermo torna sul telefono; non subito dopo un cambio.
    model.playOn(speaker)
    until { model.remote != null }
    model.phonePlaying(true)
    check("Subito dopo il cambio lo schermo resta sulla cassa", model.remote != null)
    delay(PhoneModel.MOVE_SETTLE_MS + 500)
    model.phonePlaying(true)
    check("Il telefono suona da sé: lo schermo torna al telefono", model.remote == null)

    // Pulizia: niente musica che continua.
    runCatching { devices.command("stop", speaker.id) }
    runCatching { devices.command("stop", phone.id) }
    delay(500)
    phone.client.stop()
    room.client.stop()
    repo.release()
    println(if (failures == 0) "\nTutto bene" else "\n$failures prove non riuscite")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
