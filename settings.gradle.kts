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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "carplay-android"
include(":app")
include(":core-iap2")
include(":core-airplay")
include(":adapter-vivo")
include(":adapter-xiaomi")
include(":adapter-generic")
