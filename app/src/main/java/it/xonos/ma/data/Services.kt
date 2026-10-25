package it.xonos.ma.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * I servizi di Music Assistant (i "provider": Spotify, Tidal, Jellyfin, podcast, cartelle, casse...)
 * e il loro motore di configurazione, che esiste dalla 2.10: un servizio nuovo si aggiunge con un
 * flusso a passi (moduli, un accesso nel browser, attese) che Music Assistant guida da sé; Xonos
 * disegna i passi con i campi che arrivano, senza sapere niente del servizio. Solo per gli
 * amministratori: agli altri Music Assistant rifiuta questi comandi.
 */
class Services(private val repo: MaRepository) {

    /** Quando ogni servizio è stato salvato o creato l'ultima volta (vedi [remove]). */
    private val changedAt = ConcurrentHashMap<String, Long>()

    /** I servizi configurati, abilitati o no. */
    suspend fun configured(): List<ServiceConfig> =
        (repo.command("config/providers") as? JSONArray).objects().mapNotNull(ServiceConfig::from)

    /** I tipi di servizio che si possono aggiungere (escluso ciò che è dismesso). */
    suspend fun kinds(): List<ServiceKind> =
        (repo.command("providers/manifests") as? JSONArray).objects().mapNotNull(ServiceKind::from)

    /** L'icona di un servizio (di solito un SVG), o null se Music Assistant non ne ha o è più vecchio della 2.10. */
    suspend fun icon(domain: String, monochrome: Boolean = false): ServiceIcon? = try {
        val uri = repo.command("providers/icon", 10_000) {
            put("provider", domain)
            if (monochrome) put("variant", "monochrome")
        } as? String
        uri?.let(ServiceIcon::fromDataUri)
    } catch (e: MaException) {
        null
    }

    /**
     * Un servizio configurato, con il modulo delle sue opzioni e i valori attuali. I valori arrivano
     * in ordine sparso: l'ordine dei campi è quello di config/providers/get_entries.
     */
    suspend fun config(instanceId: String): ServiceConfig {
        val service = ServiceConfig.from(repo.command("config/providers/get") { put("instance_id", instanceId) } as? JSONObject)
            ?: throw MaException("Servizio sconosciuto: $instanceId", problem = Problem.REFUSED)
        val order = try {
            (repo.command("config/providers/get_entries") { put("instance_id", instanceId) } as? JSONArray).objects().mapNotNull { it.str("key") }
        } catch (e: MaException) {
            emptyList()
        }
        val position = order.withIndex().associate { (i, key) -> key to i }
        return service.copy(fields = service.fields.sortedBy { position[it.key] ?: Int.MAX_VALUE })
    }

    /** Salva le opzioni (solo quelle cambiate, come fa l'interfaccia di Music Assistant). */
    suspend fun save(service: ServiceConfig, values: Map<String, Any?>): ServiceConfig {
        val saved = repo.command("config/providers/save", 60_000) {
            put("provider_domain", service.domain)
            put("instance_id", service.instanceId)
            put("values", JSONObject().apply { values.forEach { (key, value) -> put(key, toJson(value)) } })
        } as? JSONObject
        changed(service.instanceId)
        return ServiceConfig.from(saved) ?: config(service.instanceId)
    }

    /** Un servizio appena creato o salvato: Music Assistant lo sta per (ri)caricare. */
    fun changed(instanceId: String) {
        changedAt[instanceId] = System.currentTimeMillis()
    }

    suspend fun remove(instanceId: String) {
        // Togliere un servizio mentre si (ri)carica lascia in Music Assistant una voce rotta che blocca
        // l'elenco dei servizi. Dopo un salvataggio il servizio si ricarica un secondo più tardi:
        // prima si aspetta quel secondo, poi che finisca di caricarsi.
        val since = System.currentTimeMillis() - (changedAt[instanceId] ?: 0L)
        if (since < SETTLE_MS) delay(SETTLE_MS - since)
        withTimeoutOrNull(REMOVE_WAIT_MS) {
            while (configured().firstOrNull { it.instanceId == instanceId }?.status == "loading") delay(500)
        }
        repo.command("config/providers/remove", 60_000) { put("instance_id", instanceId) }
    }

    /** Un pulsante delle opzioni: un messaggio o un indirizzo da aprire, oppure un modulo nuovo da mostrare. */
    suspend fun action(instanceId: String, action: String): ActionResult {
        val result = repo.command("config/providers/invoke_action", 60_000) {
            put("instance_id", instanceId)
            put("action", action)
        }
        return when (result) {
            is JSONArray -> ActionResult(fields = result.objects().mapNotNull(ConfigField::from))
            is JSONObject -> ActionResult(message = result.str("message"), url = result.str("open_url"))
            else -> ActionResult()
        }
    }

    /** Comincia ad aggiungere un servizio: il primo passo del suo flusso (o subito la fine, se non chiede niente). */
    suspend fun setup(domain: String): FlowStep = step(repo.command("config/providers/setup", FLOW_TIMEOUT) { put("provider_domain", domain) })

    /** Riconfigura un servizio (per esempio per rifare l'accesso). */
    suspend fun reconfigure(instanceId: String): FlowStep =
        step(repo.command("config/providers/reconfigure", FLOW_TIMEOUT) { put("instance_id", instanceId) })

    /** Manda i valori di un modulo: il passo dopo, o lo stesso con gli errori. */
    suspend fun submit(flowId: String, values: Map<String, Any?>): FlowStep =
        step(
            repo.command("config/flows/submit", FLOW_TIMEOUT) {
                put("flow_id", flowId)
                put("values", JSONObject().apply { values.forEach { (key, value) -> put(key, toJson(value)) } })
            },
        )

    /** Il passo attuale di un flusso (tornando nell'app dopo l'accesso nel browser). */
    suspend fun current(flowId: String): FlowStep = step(repo.command("config/flows/get") { put("flow_id", flowId) })

    suspend fun abort(flowId: String) {
        runCatching { repo.command("config/flows/abort", 15_000) { put("flow_id", flowId) } }
    }

    private fun step(result: Any?): FlowStep {
        val step = FlowStep.from(result as? JSONObject) ?: throw MaException("Passo non valido", problem = Problem.REFUSED)
        if (step.type == "finish") step.instanceId?.let(::changed)
        return step
    }

    companion object {
        /** L'evento con il passo nuovo di un flusso (object_id: l'id del flusso). */
        const val FLOW_EVENT = "setup_flow_updated"

        /** Dalla 2.10 (schema 63) i servizi si aggiungono con i flussi a passi. */
        const val FLOW_SCHEMA = 63

        /** Il valore che Music Assistant mostra al posto di una password salvata: rimandato tale e quale, non la cambia. */
        const val SECRET_KEPT = "this_value_is_encrypted"

        // Aggiungere un servizio può voler dire installarne le librerie: Music Assistant aspetta fino a due minuti.
        private const val FLOW_TIMEOUT = 150_000L

        private const val REMOVE_WAIT_MS = 30_000L
        private const val SETTLE_MS = 3_000L

        fun toJson(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is List<*> -> JSONArray(value.map { toJson(it) })
            else -> value
        }
    }
}

/** Un'icona di un servizio: i byte e il tipo (image/svg+xml, image/png...). */
class ServiceIcon(val mime: String, val bytes: ByteArray) {
    companion object {
        fun fromDataUri(uri: String): ServiceIcon? {
            if (!uri.startsWith("data:")) return null
            val comma = uri.indexOf(',')
            if (comma < 0) return null
            val header = uri.substring(5, comma)
            val mime = header.substringBefore(';')
            val data = uri.substring(comma + 1)
            val bytes = if (header.endsWith(";base64")) {
                runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return null
            } else {
                java.net.URLDecoder.decode(data, "UTF-8").toByteArray()
            }
            return ServiceIcon(mime, bytes)
        }
    }
}

/** Un tipo di servizio (il manifest di un provider). */
data class ServiceKind(
    val domain: String,
    /** "music", "player", "metadata", "plugin"... */
    val type: String,
    val name: String,
    val description: String?,
    /** "stable", "beta", "alpha", "experimental"... */
    val stage: String,
    val multiInstance: Boolean,
    val builtin: Boolean,
    val documentation: String?,
    val dependsOn: String?,
) {
    companion object {
        fun from(o: JSONObject): ServiceKind? {
            val stage = o.str("stage") ?: "stable"
            if (stage == "deprecated") return null
            return ServiceKind(
                domain = o.str("domain") ?: return null,
                type = o.str("type") ?: "other",
                name = o.str("name") ?: return null,
                description = o.str("description"),
                stage = stage,
                multiInstance = o.optBoolean("multi_instance"),
                builtin = o.optBoolean("builtin"),
                documentation = o.str("documentation"),
                dependsOn = o.str("depends_on"),
            )
        }
    }
}

/** Un servizio configurato in Music Assistant, e (con config/providers/get) le sue opzioni. */
data class ServiceConfig(
    val instanceId: String,
    val domain: String,
    val type: String,
    val name: String,
    val enabled: Boolean,
    /** L'ultimo errore del servizio (accesso scaduto, server irraggiungibile...), se c'è. */
    val lastError: String?,
    /** "loaded", "error", "disabled"... (dalla 2.10). */
    val status: String?,
    val fields: List<ConfigField> = emptyList(),
) {
    companion object {
        fun from(o: JSONObject?): ServiceConfig? {
            if (o == null) return null
            val values = o.optJSONObject("values")
            return ServiceConfig(
                instanceId = o.str("instance_id") ?: return null,
                domain = o.str("domain") ?: return null,
                type = o.str("type") ?: "other",
                name = o.str("name") ?: o.str("default_name") ?: o.str("domain")!!,
                enabled = if (o.has("enabled")) o.optBoolean("enabled") else true,
                // Dalla 2.10 è un oggetto (codice, messaggio, chiave della traduzione); prima era un testo.
                lastError = o.optJSONObject("last_error")?.let { it.str("message") ?: it.str("error_code") } ?: o.str("last_error"),
                status = o.str("status"),
                fields = values?.keys()?.asSequence()?.mapNotNull { key -> values.optJSONObject(key)?.let(ConfigField::from) }?.toList().orEmpty(),
            )
        }
    }
}

/** Un'opzione di un campo a scelta. */
data class FieldOption(val title: String, val value: Any?, val disabled: Boolean = false, val description: String? = null)

/**
 * Un campo di un modulo di Music Assistant (un ConfigEntry): il tipo dice come disegnarlo
 * ("boolean", "string", "secure_string", "integer", "float", "label", "divider", "action",
 * "alert", "url", "image", "icon", "pairing_code", "splitted_string").
 */
data class ConfigField(
    val key: String,
    val type: String,
    /** Già tradotta da Music Assistant nella lingua della connessione (vedi MaClient.authenticate). */
    val label: String,
    val description: String?,
    val defaultValue: Any?,
    val value: Any?,
    val required: Boolean,
    val options: List<FieldOption>,
    val range: IntRange?,
    val multiValue: Boolean,
    val expanded: Boolean,
    val dependsOn: String?,
    val dependsOnValue: Any?,
    val dependsOnValueNot: Any?,
    val hidden: Boolean,
    val readOnly: Boolean,
    val category: String,
    val categoryLabel: String?,
    val action: String?,
    val actionLabel: String?,
    val advanced: Boolean,
    /** Per i codici di abbinamento: '#' una cifra, 'X' una lettera o cifra, il resto separatori. */
    val format: String?,
    val helpLink: String?,
) {
    /** Il valore da mostrare: quello attuale o, se manca, quello predefinito. */
    val current: Any? get() = value ?: defaultValue

    /** I campi che non si compilano (testi, separatori, pulsanti...). */
    val display: Boolean get() = type in DISPLAY

    companion object {
        private val DISPLAY = setOf("label", "divider", "action", "alert", "icon", "image", "url")

        fun from(o: JSONObject): ConfigField? {
            val key = o.str("key") ?: return null
            val range = o.optJSONArray("range")?.takeIf { it.length() == 2 }?.let { it.optInt(0)..it.optInt(1) }
            return ConfigField(
                key = key,
                type = o.str("type") ?: "unknown",
                label = o.str("label") ?: key,
                description = o.str("description"),
                defaultValue = plain(o.opt("default_value")),
                value = plain(o.opt("value")),
                required = o.optBoolean("required", true),
                options = o.optJSONArray("options").objects().map { option ->
                    FieldOption(
                        title = option.str("title") ?: option.opt("value")?.toString().orEmpty(),
                        value = plain(option.opt("value")),
                        disabled = option.optBoolean("disabled"),
                        description = option.str("description"),
                    )
                },
                range = range,
                multiValue = o.optBoolean("multi_value"),
                expanded = o.optBoolean("expanded_options"),
                dependsOn = o.str("depends_on"),
                dependsOnValue = plain(o.opt("depends_on_value")),
                dependsOnValueNot = plain(o.opt("depends_on_value_not")),
                hidden = o.optBoolean("hidden"),
                readOnly = o.optBoolean("read_only"),
                category = o.str("category") ?: "generic",
                categoryLabel = o.str("category_label"),
                action = o.str("action"),
                actionLabel = o.str("action_label"),
                advanced = o.optBoolean("advanced"),
                format = o.str("format"),
                helpLink = o.str("help_link"),
            )
        }

        /** Un valore JSON come valore Kotlin: null, String, Number, Boolean o List. */
        fun plain(value: Any?): Any? = when (value) {
            null, JSONObject.NULL -> null
            is JSONArray -> (0 until value.length()).map { plain(value.opt(it)) }
            else -> value
        }

        /**
         * Se un campo va mostrato (e compilato), dati i valori degli altri: depends_on dice da quale
         * campo dipende; senza depends_on_value basta che quel campo sia "vero".
         */
        fun met(field: ConfigField, values: Map<String, Any?>): Boolean {
            val other = field.dependsOn ?: return true
            if (!values.containsKey(other)) return false
            val value = values[other]
            return when {
                field.dependsOnValue != null -> same(value, field.dependsOnValue)
                field.dependsOnValueNot != null -> !same(value, field.dependsOnValueNot)
                else -> truthy(value)
            }
        }

        private fun same(a: Any?, b: Any?): Boolean =
            if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

        private fun truthy(value: Any?): Boolean = when (value) {
            null -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotEmpty()
            is List<*> -> value.isNotEmpty()
            else -> true
        }
    }
}

/** Il risultato di un pulsante delle opzioni. */
data class ActionResult(val message: String? = null, val url: String? = null, val fields: List<ConfigField> = emptyList())

/** Un passo di un flusso di configurazione. */
data class FlowStep(
    val flowId: String,
    val stepId: String,
    /** "form", "external" (aprire un indirizzo nel browser), "progress", "finish", "abort". */
    val type: String,
    val title: String?,
    /** In Markdown, come lo scrive Music Assistant (link, grassetto, elenchi). */
    val description: String?,
    val fields: List<ConfigField>,
    /** Errori per campo; "base" è l'errore del modulo intero. */
    val errors: Map<String, String>,
    val lastStep: Boolean?,
    val url: String?,
    val progressText: String?,
    val progress: Float?,
    /** Un'immagine da mostrare durante l'attesa (per esempio un QR code), come data URI. */
    val image: String?,
    /** Quando scade il passo (secondi dal 1970), se scade. */
    val expiresAt: Double?,
    /** Alla fine: l'istanza creata o aggiornata. */
    val instanceId: String?,
    /** Perché il flusso si è fermato. */
    val reason: String?,
) {
    val done: Boolean get() = type == "finish" || type == "abort"

    companion object {
        fun from(o: JSONObject?): FlowStep? {
            if (o == null) return null
            val errors = o.optJSONObject("errors")
            return FlowStep(
                flowId = o.str("flow_id") ?: return null,
                stepId = o.str("step_id").orEmpty(),
                type = o.str("type") ?: "unknown",
                title = o.str("title"),
                description = o.str("description"),
                fields = o.optJSONArray("entries").objects().mapNotNull(ConfigField::from),
                errors = errors?.keys()?.asSequence()?.associateWith { errors.optString(it) }.orEmpty(),
                lastStep = if (o.isNull("last_step")) null else o.optBoolean("last_step"),
                url = o.str("url"),
                progressText = o.str("progress_text"),
                progress = if (o.isNull("progress")) null else o.optDouble("progress").toFloat(),
                image = o.str("image"),
                expiresAt = if (o.isNull("expires_at")) null else o.optDouble("expires_at"),
                instanceId = o.optJSONObject("result")?.str("instance_id"),
                reason = o.str("reason"),
            )
        }
    }
}
