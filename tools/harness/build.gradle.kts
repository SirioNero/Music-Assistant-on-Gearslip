plugins {
    kotlin("jvm") version "2.2.0"
    application
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.json:json:20240303")
}
application {
    mainClass.set(providers.gradleProperty("main").getOrElse("harness.MainKt"))
    applicationDefaultJvmArgs = listOf("-Dxonos.res=${rootDir}/../../app/src/main/res", "-Dstdout.encoding=UTF-8")
}
