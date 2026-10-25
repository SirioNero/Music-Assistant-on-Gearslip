package it.xonos.ma

import android.content.Context
import android.content.res.Configuration
import it.xonos.ma.data.Connection
import it.xonos.ma.data.MaException
import it.xonos.ma.data.Problem
import java.util.Locale

/**
 * Le lingue di Xonos, ciascuna con il suo nome nella sua lingua. Quella scelta sul telefono
 * vale anche per il menu in auto; finché non se ne sceglie una, Xonos parla inglese.
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

    /** Lo stesso contesto, con le scritte in questa lingua invece che in quella del telefono. */
    fun wrap(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
    }

    companion object {
        val DEFAULT = ENGLISH

        fun of(tag: String?) = entries.firstOrNull { it.tag == tag } ?: DEFAULT
    }
}

/** Che cosa non va, detto con le parole di Xonos nella lingua di [context]. */
fun describe(context: Context, problem: Problem, detail: String?): String = when (problem) {
    Problem.BAD_ADDRESS -> context.getString(R.string.problem_bad_address, detail.orEmpty())
    Problem.NO_ANSWER -> if (detail != null) context.getString(R.string.problem_no_answer_from, detail) else context.getString(R.string.problem_no_answer)
    Problem.NOT_MUSIC_ASSISTANT -> context.getString(R.string.problem_not_ma)
    Problem.TOO_OLD -> context.getString(R.string.problem_too_old, detail.orEmpty())
    Problem.BAD_TOKEN -> context.getString(R.string.problem_bad_token)
    Problem.DENIED -> context.getString(R.string.problem_denied)
    Problem.HTTP -> context.getString(R.string.problem_http, detail.orEmpty())
    Problem.NETWORK -> if (detail != null) context.getString(R.string.problem_network, detail) else context.getString(R.string.problem_unreachable)
    Problem.LOST -> context.getString(R.string.problem_lost)
    Problem.NOT_CONNECTED -> context.getString(R.string.problem_not_connected)
    Problem.REFUSED -> if (detail != null) context.getString(R.string.problem_refused, detail) else context.getString(R.string.error_unknown)
}

/** Un errore qualsiasi: quelli di Music Assistant con le parole di Xonos, gli altri come vengono. */
fun describe(context: Context, error: Throwable): String = when (error) {
    is MaException -> describe(context, error.problem, error.detail)
    else -> error.message ?: context.getString(R.string.error_unknown)
}

/** Il problema da mostrare in auto per lo stato della connessione; null se va tutto bene. */
fun describe(context: Context, connection: Connection): String? = when (connection) {
    Connection.NotConfigured -> context.getString(R.string.problem_not_configured)
    is Connection.Failed -> describe(context, connection.problem, connection.detail)
    is Connection.Connecting, is Connection.Connected -> null
}
