package it.xonos.ma.phone.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Un Music Assistant trovato nella rete di casa. Music Assistant si annuncia con mDNS come
 * servizio `_mass._tcp` e nei campi TXT mette le sue informazioni (le stesse del primo messaggio
 * dell'API): id, nome, versione, indirizzo interno ed eventuale indirizzo esterno.
 */
data class FoundServer(
    val id: String,
    val name: String,
    val version: String,
    /** L'indirizzo in casa (internal_url, o base_url per le versioni prima della 2.10). */
    val url: String,
    /** L'indirizzo da fuori casa, se in Music Assistant è impostato. */
    val remoteUrl: String?,
) {
    companion object {
        /** Dai campi TXT dell'annuncio; [host] e [port] servono se l'indirizzo non c'è. */
        fun from(txt: Map<String, String?>, host: String?, port: Int): FoundServer? {
            fun value(key: String) = txt[key]?.trim()?.takeIf { it.isNotEmpty() && it != "None" }
            val url = value("internal_url") ?: value("base_url") ?: host?.let { "http://${if (':' in it) "[$it]" else it}:$port" } ?: return null
            return FoundServer(
                id = value("server_id") ?: url,
                name = value("name") ?: "Music Assistant",
                version = value("server_version") ?: "?",
                url = url.trimEnd('/'),
                remoteUrl = value("external_url")?.trimEnd('/'),
            )
        }

        /** Il tipo di servizio mDNS di Music Assistant. */
        const val SERVICE_TYPE = "_mass._tcp"
    }
}

/** Chi cerca Music Assistant nella rete: sul telefono NsdManager, nelle prove uno finto. */
interface ServerFinder {
    /** I server trovati finora, in ordine di arrivo. */
    val servers: StateFlow<List<FoundServer>>

    /** Vero mentre cerca. */
    val searching: StateFlow<Boolean>

    /** Comincia a cercare (finché lo schermo delle impostazioni è aperto). */
    fun start()

    fun stop()
}

/** Un cercatore che trova sempre gli stessi server: per il banco di prova e le schermate. */
class FixedFinder(found: List<FoundServer> = emptyList()) : ServerFinder {
    private val _servers = MutableStateFlow(found)
    override val servers: StateFlow<List<FoundServer>> = _servers.asStateFlow()
    private val _searching = MutableStateFlow(false)
    override val searching: StateFlow<Boolean> = _searching.asStateFlow()

    override fun start() {
        _searching.value = true
    }

    override fun stop() {
        _searching.value = false
    }
}
