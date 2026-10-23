// :core:storage — settings + cookie persistence + Room cache (Android library).
//
// Depends on :core:net (CookieStore contract, Track model) and exposes the
// DataStore-backed implementations consumed by :core:player and :app.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.rc.harken.storage"
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

// Room schema export location (checked-in JSON, future migrations).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // api：UpdateRepository 的公开能力围绕 DataStore 持久化（待更新状态），
    // 上游 feature 模块（如 :feature:settings）调用其方法时需在编译期可见 Preferences/DataStore。
    api(libs.androidx.datastore.preferences)

    // :core:net exposes kotlinx-serialization / coroutines as `api`.
    implementation(project(":core:net"))
    // AppSettings carries AudioQuality (architecture §4.1), which lives in
    // :core:playback — the interface signature implies this edge.
    implementation(project(":core:playback"))

    // Room cache (P1): play history + liked songs + downloaded songs.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // 下载直连落盘（OkHttp）；net 已 api 暴露，此处显式声明更稳。
    implementation(libs.okhttp)
}
