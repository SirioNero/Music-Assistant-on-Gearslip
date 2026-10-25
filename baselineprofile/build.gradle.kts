import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Il Baseline Profile di Xonos e le misure (avvio, scorrimento, lettore, playlist lunga).
// Girano su un dispositivo collegato: su GitHub l'emulatore del workflow "Misure" (vedi tools/measure),
// con il Music Assistant di prova in Docker raggiungibile dall'emulatore a 10.0.2.2.
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "it.xonos.ma.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // Generare un Baseline Profile senza root vuole Android 13 (API 33); le misure vanno dalla 28.
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Sull'emulatore i numeri valgono per confrontare prima e dopo, non in assoluto.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,LOW-BATTERY"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}

// Le misure sanno così quale app guidare (l'id dell'APK provato).
androidComponents {
    onVariants { variant ->
        val loader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put("targetAppId", variant.testedApks.map { loader.load(it)?.applicationId })
    }
}
