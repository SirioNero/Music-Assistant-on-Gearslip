package it.xonos.ma.data

import it.xonos.ma.Language

/** Stub di Settings per la JVM: indirizzo e token dall'ambiente, il resto in memoria. */
class Settings {
    data class Config(val homeUrl: String, val remoteUrl: String, val token: String) {
        val urls: List<String> get() = listOf(homeUrl, remoteUrl).filter { it.isNotBlank() }.distinct()
        val isComplete: Boolean get() = urls.isNotEmpty() && token.isNotBlank()
    }

    private var config = Config(System.getenv("MA_URL") ?: "http://127.0.0.1:8095", "", System.getenv("MA_TOKEN") ?: "")

    fun load() = config

    fun save(config: Config) {
        this.config = config
    }

    var language: Language = Language.of(System.getenv("LANG_XONOS"))

    var playerName: String = DEFAULT_NAME
        set(value) {
            field = value.trim().ifEmpty { DEFAULT_NAME }
        }

    companion object {
        const val DEFAULT_NAME = "Xonos"

        fun normalizeUrl(raw: String): String {
            val url = raw.trim().trimEnd('/')
            if (url.isEmpty() || url.startsWith("http://") || url.startsWith("https://")) return url
            return "http://$url"
        }
    }
}
