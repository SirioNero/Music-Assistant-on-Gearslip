package it.xonos.ma.phone.model

/**
 * Music Assistant sul telefono stesso: Xonos lo scarica solo se la persona lo chiede ("Fai diventare Xonos
 * il tuo server Music Assistant") e lo fa girare come processo a sé. Risponde solo al telefono, a [url],
 * come un Music Assistant qualsiasi. Dove non può girare (un telefono a 32 bit) non c'è: al suo posto null.
 */
interface PhoneServerControl {
    enum class State { STOPPED, STARTING, RUNNING, FAILED }

    /** L'indirizzo a cui risponde: solo il telefono stesso. */
    val url: String

    val state: State

    /** Perché non è partito o si è fermato, se è successo. */
    val error: String

    /** Il pacchetto è sul telefono, nella versione che serve a questa versione di Xonos. */
    val installed: Boolean

    /** C'è quello di una versione precedente: va scaricato di nuovo, chiedendolo come la prima volta. */
    val outdated: Boolean

    /** Quanto pesa il pacchetto da scaricare, in byte; null se non lo si sa (per esempio senza rete). */
    suspend fun downloadSize(): Long?

    /**
     * Scarica il pacchetto, ne controlla la firma e lo mette al suo posto; [progress] riceve i byte arrivati
     * e il totale. Se qualcosa va storto lancia un'eccezione col perché, e non resta niente a metà.
     */
    suspend fun download(progress: (done: Long, total: Long) -> Unit)

    /** Toglie dal telefono il pacchetto e i dati di Music Assistant (account, servizi, libreria). */
    fun remove()

    /** Lo avvia, se non gira già, e aspetta che risponda; false se non parte (il perché in [error]). */
    suspend fun start(): Boolean

    /** Lo ferma, se gira. */
    fun stop()
}
