package it.xonos.ma

/**
 * Le lingue di Xonos, ciascuna con il suo nome nella sua lingua. Quella scelta sul telefono
 * vale anche per il menu in auto; finché non se ne sceglie una, Xonos parla inglese.
 * Le scritte nella lingua scelta si prendono con [wrap] (in Texts.kt).
 */
enum class Language(
    val tag: String,
    val label: String,
    /** Come la chiama Music Assistant, che traduce i suoi moduli (i servizi) nella lingua della connessione. */
    val maLocale: String,
) {
    ENGLISH("en", "English", "en"),
    ITALIAN("it", "Italiano", "it"),
    SPANISH("es", "Español", "es"),
    GERMAN("de", "Deutsch", "de"),
    FRENCH("fr", "Français", "fr"),
    // In Music Assistant la traduzione pt_PT è molto più completa di pt_BR.
    PORTUGUESE("pt", "Português", "pt_PT"),
    GREEK("el", "Ελληνικά", "el_GR"),
    RUSSIAN("ru", "Русский", "ru_RU"),
    CHINESE("zh-CN", "简体中文", "zh_CN"),
    ;

    companion object {
        val DEFAULT = ENGLISH

        fun of(tag: String?) = entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }
}
