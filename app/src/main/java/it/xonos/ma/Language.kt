package it.xonos.ma

/**
 * Le lingue di Xonos, ciascuna con il suo nome nella sua lingua. Quella scelta sul telefono
 * vale anche per il menu in auto; finché non se ne sceglie una, Xonos parla inglese.
 * Le scritte nella lingua scelta si prendono con [wrap] (in Texts.kt).
 */
enum class Language(val tag: String, val label: String) {
    ENGLISH("en", "English"),
    ITALIAN("it", "Italiano"),
    SPANISH("es", "Español"),
    GERMAN("de", "Deutsch"),
    FRENCH("fr", "Français"),
    PORTUGUESE("pt", "Português"),
    GREEK("el", "Ελληνικά"),
    RUSSIAN("ru", "Русский"),
    CHINESE("zh-CN", "简体中文"),
    ;

    companion object {
        val DEFAULT = ENGLISH

        fun of(tag: String?) = entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }
}
