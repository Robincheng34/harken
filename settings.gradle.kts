// Harken 倾耳 — Gradle settings.
//
// Module layout follows docs/harken-android-architecture.md §3:
//   :core:net / :core:playback are pure Kotlin/JVM modules (no Android SDK
//   needed — the QA command `./gradlew :core:net:test :core:playback:test`
//   must run on a bare JDK machine).
//   :app and every other Android module are only included when an Android
//   SDK is available (local.properties sdk.dir or ANDROID_HOME), so that
//   SDK-less QA machines never configure AGP at all.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

// Detects an Android SDK without requiring one.
fun hasAndroidSdk(): Boolean {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (fromEnv != null && file(fromEnv).isDirectory) return true
    val local = file("local.properties")
    if (local.isFile) {
        val props = java.util.Properties()
        local.inputStream().use(props::load)
        val sdkDir = props.getProperty("sdk.dir")
        if (sdkDir != null && file(sdkDir).isDirectory) return true
    }
    return false
}

rootProject.name = "Harken"

include(":core:net")
include(":core:playback")
if (hasAndroidSdk()) {
    include(":app")
    include(":core:storage")
    include(":core:player")
    include(":design-system")
    include(":feature:login")
    include(":feature:home")
    include(":feature:explore")
    include(":feature:search")
    include(":feature:detail")
    include(":feature:fm")
    include(":feature:library")
    include(":feature:player")
    include(":feature:settings")
}
