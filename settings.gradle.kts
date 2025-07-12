pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
    }
}

dependencyResolutionManagement {
    repositories {
        google() // Репозиторий для Android
        mavenCentral() // Репозиторий Maven Central
        maven { url = uri("https://jitpack.io") }
    }
}


rootProject.name = "SOS Taxi"
include(":app")
