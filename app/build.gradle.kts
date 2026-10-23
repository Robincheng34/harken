import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// 发布签名（可选）：签名材料在仓库根 keystore.properties + harken-release.jks，
// 两者均不入 git（见 .gitignore）。文件不存在（如他人 clone 源码）时
// 不创建 signingConfig，release 输出未签名 APK、仍可正常构建。
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.isFile) {
        propsFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystoreProperties["storeFile"] != null

android {
    namespace = "com.rc.harken"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rc.harken"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 本项目尚未维护完整 R8 规则，release 关闭混淆/收缩，保证功能一致。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
    // Core layers.
    implementation(project(":core:net"))
    implementation(project(":core:playback"))
    implementation(project(":core:player"))
    implementation(project(":core:storage"))
    implementation(project(":design-system"))

    // Feature modules (UI surfaces).
    implementation(project(":feature:login"))
    implementation(project(":feature:home"))
    implementation(project(":feature:explore"))
    implementation(project(":feature:search"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:fm"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // AppGraph 装配 Cookie/设置共用的偏好 DataStore（preferencesDataStore 委托）。
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling.preview)

    // 在线检测更新单元测试（纯逻辑，不依赖 Robolectric）。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}
