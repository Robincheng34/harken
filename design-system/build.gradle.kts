// :design-system — Compose theme tokens (Android library).
//
// T12 只交付主题 Token（颜色/字体/圆角/动效/布局）；基础组件在 T13/T14。

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose.compiler)
}

android {
    namespace = "com.rc.harken.design"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Core domain/contracts surfaced through the composition locals below.
    implementation(project(":core:net"))
    implementation(project(":core:playback"))
    implementation(project(":core:player"))
    implementation(project(":core:storage"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling.preview)

    // 封面加载（Coil）与取色（Palette）：CoverCard / ArtworkPalette。
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)
}
