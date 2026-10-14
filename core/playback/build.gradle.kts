// :core:playback — pure Kotlin/JVM playback queue state machine (no Android
// framework dependency; QA runs `./gradlew :core:playback:test` on a bare JDK).

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // 队列状态机仅依赖 :core:net 的 Track 模型（纯 JVM）。
    implementation(project(":core:net"))

    testImplementation(libs.junit)
}
