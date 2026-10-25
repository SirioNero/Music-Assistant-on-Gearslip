package android.os

/** Stub di android.os.Trace per provare il codice sulla JVM: le sezioni non vanno da nessuna parte. */
object Trace {
    @JvmStatic fun beginSection(sectionName: String) = Unit
    @JvmStatic fun endSection() = Unit
}
