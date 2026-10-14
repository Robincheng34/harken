// :core:net — pure Kotlin/JVM protocol layer (weapi/eapi crypto, models,
// transport, API surface, lyrics parser). No Android framework dependency,
// so the golden-vector tests run on a bare JDK (QA command:
// `./gradlew :core:net:test :core:playback:test`).

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Exposed as `api`: the model/transport public surface references these
    // types (e.g. Track carries kotlinx.serialization annotations, NeteaseClient
    // takes an OkHttpClient), so consumers need them on their compile classpath.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
