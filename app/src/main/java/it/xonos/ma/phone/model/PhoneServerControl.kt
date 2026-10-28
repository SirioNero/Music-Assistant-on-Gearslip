package it.xonos.ma.phone.model

/**
 * Music Assistant sul telefono stesso, nella versione di Xonos che lo porta dentro di sé
 * (Xonos-server.apk, modulo server). Risponde solo al telefono, a [url], come un Music Assistant
 * qualsiasi. Nella versione senza (quella di F-Droid) non c'è: al suo posto null.
 */
interface PhoneServerControl {
    enum class State { STOPPED, STARTING, RUNNING, FAILED }

    /** L'indirizzo a cui risponde: solo il telefono stesso. */
    val url: String

    val state: State

    /** Perché non è partito o si è fermato, se è successo. */
    val error: String

    /** Lo avvia, se non gira già, e aspetta che risponda; false se non parte (il perché in [error]). */
    suspend fun start(): Boolean
}
