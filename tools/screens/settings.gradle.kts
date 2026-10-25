pluginManagement {
    repositories {
        // Lo specchio di Maven Central: nella sandbox di Claude Maven Central risponde spesso 429.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        // Alcune librerie androidx che Compose Desktop usa stanno solo sul Maven di Google, che dalla
        // sandbox non si raggiunge: lì le prende da libs/ (le prepara il workflow "Schermate", vedi README).
        val libs = file("libs")
        if (libs.isDirectory) {
            maven(libs.toURI()) {
                content { includeGroupByRegex("androidx\\..*") }
                // La cache di Gradle su GitHub a volte ha il jar senza il suo .pom.
                metadataSources { gradleMetadata(); mavenPom(); artifact() }
            }
        }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        google()
    }
}
rootProject.name = "screens"
