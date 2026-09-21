pluginManagement {
    repositories {
        // Gradle Plugin Portal first so the Kotlin Compose plugin (2.0.21) resolves correctly.
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "CloudGrip"
include(":app")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")