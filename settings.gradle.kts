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
