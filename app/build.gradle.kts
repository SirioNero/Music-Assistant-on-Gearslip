import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Su GitHub ogni compilazione ha un numero crescente: così ogni APK nuovo si installa sopra il precedente.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// La chiave di firma NON sta nel repository: la compilazione su GitHub la ricrea dal secret AUTORADIO_KEYSTORE.
// Senza chiave (per esempio chi contribuisce, o una build sul proprio computer) si usa la chiave di debug di Android.
val autoradioKeystore = file("autoradio.keystore")
val hasAutoradioKey = autoradioKeystore.exists()
val keystorePassword = System.getenv("AUTORADIO_KEYSTORE_PASSWORD")?.ifBlank { null } ?: "autoradio-android"

android {
    namespace = "it.autoradio.ma"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.autoradio.ma"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        // Deve restare uguale al tag della release (v0.1.N nel workflow), che Obtainium confronta con l'app installata.
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        if (hasAutoradioKey) {
            create("autoradio") {
                storeFile = autoradioKeystore
                storePassword = keystorePassword
                // Il workflow legge l'alias dalla chiave stessa: va bene anche quella di Plancia.
                keyAlias = System.getenv("AUTORADIO_KEY_ALIAS")?.ifBlank { null } ?: "autoradio"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasAutoradioKey) signingConfig = signingConfigs.getByName("autoradio")
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasAutoradioKey) signingConfig = signingConfigs.getByName("autoradio")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
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

dependencies {
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
