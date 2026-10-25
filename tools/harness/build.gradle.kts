plugins {
    kotlin("jvm") version "2.2.0"
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.json:json:20240303")
    // Solo gli stati osservabili (mutableStateOf) del modello del telefono: il runtime 1.5 è l'ultimo tutto su
    // Maven Central. L'interfaccia vera si disegna su GitHub (tools/screens), dove c'è il Maven di Google.
    implementation("org.jetbrains.compose.runtime:runtime-desktop:1.5.12")
}
application {
    mainClass.set(providers.gradleProperty("main").getOrElse("harness.MainKt"))
    applicationDefaultJvmArgs = listOf("-Dxonos.res=${rootDir}/../../app/src/main/res", "-Dstdout.encoding=UTF-8")
}
