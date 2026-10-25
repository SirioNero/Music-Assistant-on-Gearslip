plugins {
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
    // Compose Multiplatform 1.9.3 è Compose 1.9.4 e Material 3 1.4.0: le stesse versioni dell'app.
    id("org.jetbrains.compose") version "1.9.3"
    application
}
dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.json:json:20240303")
}
application {
    mainClass.set("screens.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dxonos.res=${rootDir}/../../app/src/main/res",
        "-Djava.awt.headless=true",
        "-Dstdout.encoding=UTF-8",
    )
}
