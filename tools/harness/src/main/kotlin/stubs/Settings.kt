package it.xonos.ma.data

/** Stub di Settings per la JVM: legge indirizzo e token dall'ambiente. */
class Settings {
    data class Config(val homeUrl: String, val remoteUrl: String, val token: String) {
        val urls: List<String> get() = listOf(homeUrl, remoteUrl).filter { it.isNotBlank() }.distinct()
        val isComplete: Boolean get() = urls.isNotEmpty() && token.isNotBlank()
    }
    fun load() = Config(System.getenv("MA_URL") ?: "http://127.0.0.1:8095", "", System.getenv("MA_TOKEN") ?: "")
}
