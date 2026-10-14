// :core:player — ExoPlayer playback engine + MediaSessionService (Android library).
//
// Depends on :core:net (API/models), :core:playback (queue state machine) and
// :core:storage (settings for the quality ladder).

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "im.missuo.kumone.player"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Media3: ExoPlayer engine + MediaSessionService/notification.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    // Notification artwork via Coil (CoilBitmapLoader).
    implementation(libs.coil.compose)

    implementation(project(":core:net"))
    implementation(project(":core:playback"))
    implementation(project(":core:storage"))

    // JVM unit tests for the URL-resolution fallback chain (SongUrlResolver).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
