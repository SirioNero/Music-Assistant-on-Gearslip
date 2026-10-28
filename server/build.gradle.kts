import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Python per Android (MIT). La versione è qui e non nel progetto principale: la build di F-Droid,
    // che toglie server/, non lo scarica nemmeno.
    id("com.chaquo.python") version "17.2.0"
}

// Il server di Music Assistant dentro Xonos: Python 3.13 (Chaquopy), Music Assistant preparato da
// ma/prepare.py e i pacchetti di requirements.txt. Per compilarlo serve Python 3.13 sulla macchina
// (python3.13 sul PATH, o il percorso nella proprietà xonosPython) e la rete: il sorgente di Music
// Assistant e i pacchetti si scaricano.
val pythonCommand: String = providers.gradleProperty("xonosPython").getOrElse("python3.13")
val musicAssistantVersion = "2.10.8"
val preparedSources = layout.buildDirectory.dir("music-assistant")
val serverWheels = layout.buildDirectory.dir("wheels")

val prepareMusicAssistant by tasks.registering(Exec::class) {
    description = "Prepara il sorgente di Music Assistant per il telefono."
    inputs.file("ma/prepare.py")
    inputs.property("version", musicAssistantVersion)
    outputs.dir(preparedSources)
    commandLine(
        pythonCommand, file("ma/prepare.py").path,
        "--version", musicAssistantVersion,
        "--out", preparedSources.get().asFile.path,
    )
}

val prepareServerWheels by tasks.registering(Exec::class) {
    description = "Costruisce le wheel al posto di quelle che per Android non ci sono."
    inputs.file("ma/wheels.py")
    inputs.dir("wheels")
    outputs.dir(serverWheels)
    commandLine(pythonCommand, file("ma/wheels.py").path, "--out", serverWheels.get().asFile.path)
}

// ffmpeg e ffprobe, che Music Assistant vuole per partire: compilati dal sorgente con l'NDK (vedi
// ffmpeg/build.sh, che non rifà niente se l'uscita è già della sua versione). L'NDK è quello di
// ANDROID_NDK, o ANDROID_NDK_HOME / ANDROID_NDK_LATEST_HOME come sulle macchine di GitHub.
val ffmpegLibraries = layout.buildDirectory.dir("ffmpeg")
val androidNdk: Provider<String> = providers.environmentVariable("ANDROID_NDK")
    .orElse(providers.environmentVariable("ANDROID_NDK_HOME"))
    .orElse(providers.environmentVariable("ANDROID_NDK_LATEST_HOME"))
val serverAbis = listOf("arm64-v8a", "x86_64")
val buildFfmpeg = serverAbis.map { abi ->
    val suffix = abi.split('-', '_').joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }
    tasks.register<Exec>("buildFfmpeg$suffix") {
        description = "Compila ffmpeg e ffprobe per Android ($abi)."
        inputs.file("ffmpeg/build.sh")
        outputs.dir(ffmpegLibraries.map { it.dir(abi) })
        commandLine("bash", file("ffmpeg/build.sh").path, abi, ffmpegLibraries.get().asFile.path)
        doFirst {
            environment(
                "ANDROID_NDK",
                androidNdk.orNull ?: throw GradleException("Per compilare ffmpeg serve ANDROID_NDK (la cartella dell'NDK)."),
            )
        }
    }
}

android {
    namespace = "it.xonos.ma.server"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // I telefoni (arm64) e l'emulatore delle prove (x86_64): Python 3.13 per Android c'è solo a 64 bit.
        ndk {
            abiFilters += serverAbis
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(ffmpegLibraries)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython(pythonCommand)
        // Music Assistant legge da disco i suoi file (manifest dei servizi, traduzioni, icone) e guarda
        // quali servizi hanno setup_flow.py: si estrae tutto, .py compresi.
        extractPackages("music_assistant")
        pip {
            options("--find-links", serverWheels.get().asFile.path)
            install("-r", "requirements.txt")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir(preparedSources)
        }
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("PythonSources")) dependsOn(prepareMusicAssistant)
    if (name.startsWith("install") && name.endsWith("PythonRequirements")) dependsOn(prepareServerWheels)
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) dependsOn(buildFfmpeg)
}
