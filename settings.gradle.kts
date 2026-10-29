pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Xonos"
include(":app")
// Il Baseline Profile dell'app e le misure di avvio e scorrimento (si eseguono su un emulatore, vedi tools/measure).
include(":baselineprofile")
// Il server di Music Assistant dentro Xonos (Python con Chaquopy, vedi server/). La ricetta di F-Droid
// toglie la cartella: l'app che pubblica F-Droid non lo contiene.
if (file("server").isDirectory) include(":server")
