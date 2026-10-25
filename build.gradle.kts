plugins {
    id("com.android.application") version "8.11.1" apply false
    id("com.android.test") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    // Baseline Profile e misure (modulo baselineprofile): la versione è quella di androidx.benchmark.
    id("androidx.baselineprofile") version "1.4.1" apply false
}
