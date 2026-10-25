import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

// Su GitHub ogni compilazione ha un numero crescente: così ogni APK nuovo si installa sopra il precedente.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// La chiave di firma NON sta nel repository: la compilazione su GitHub la ricrea dal secret XONOS_KEYSTORE.
// Senza chiave (per esempio chi contribuisce, o una build sul proprio computer) si usa la chiave di debug di Android.
val xonosKeystore = file("xonos.keystore")
val hasXonosKey = xonosKeystore.exists()
val keystorePassword = System.getenv("XONOS_KEYSTORE_PASSWORD")?.ifBlank { null } ?: "xonos-android"

android {
    namespace = "it.xonos.ma"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.xonos.ma"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        // Deve restare uguale al tag della release (v0.1.N nel workflow), che Obtainium confronta con l'app installata.
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        if (hasXonosKey) {
            create("xonos") {
                storeFile = xonosKeystore
                storePassword = keystorePassword
                // Il workflow legge l'alias dalla chiave stessa: va bene anche quella di Plancia.
                keyAlias = System.getenv("XONOS_KEY_ALIAS")?.ifBlank { null } ?: "xonos"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasXonosKey) signingConfig = signingConfigs.getByName("xonos")
        }
        getByName("release") {
            isMinifyEnabled = false
            // Senza la chiave di Xonos (le misure su GitHub, chi compila per sé) l'APK si firma con
            // quella di debug, così si installa lo stesso; le release si pubblicano solo con la chiave.
            signingConfig = signingConfigs.getByName(if (hasXonosKey) "xonos" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Il profilo generato (vedi baselineprofile/) sta in src/main/generated/baselineProfiles.
baselineProfile {
    mergeIntoMain = true
}

dependencies {
    baselineProfile(project(":baselineprofile"))
    // Installa il Baseline Profile anche quando l'APK non arriva dal Play Store (Obtainium).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Lo schermo del telefono. Le versioni sono quelle di Compose Multiplatform 1.9.3, che il banco di
    // prova (tools/screens) usa per disegnare le stesse schermate sulla JVM.
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.9.4")
    implementation("androidx.compose.foundation:foundation:1.9.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
}
