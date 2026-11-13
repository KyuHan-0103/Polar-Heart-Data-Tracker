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
        // Polar BLE SDK is only published on JitPack. Restrict JitPack to Polar's
        // group so no other dependency can be resolved from it.
        maven("https://jitpack.io") { content { includeGroup("com.github.polarofficial") } }
    }
}
rootProject.name = "H10Recorder"
include(":app")
