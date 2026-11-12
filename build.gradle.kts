// Harken 倾耳 — root build script.
// Plugins are declared here with `apply false` so subprojects can apply them;
// versions are owned solely by gradle/libs.versions.toml.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}

// 全局 lint 配置：Kotlin 2.1.x 把 class metadata 写成 2.1.0，但 AGP 8.9.1 自带的
// kotlinx-metadata-jvm 只认到 2.0.0，ComposableCoroutineCreationDetector 解析含
// coroutine 调用的源码（如 PlaybackEngine.kt）会抛
// "Provided Metadata instance has version 2.1.0, while maximum supported version is 2.0.0"
// 而中断整轮 lint（lint 自身 bug，非代码问题）。
// 每个 Android 模块的 lintAnalyze 任务各自读自己的 lint 配置、互不影响，故必须在
// 根工程对所有 Android 模块统一屏蔽该检测器；等到 AGP 升级到自带兼容 metadata 2.1
// 的 kotlinx-metadata 后整段移除。
import com.android.build.api.dsl.CommonExtension

subprojects {
    afterEvaluate {
        extensions.findByType(CommonExtension::class.java)?.lint {
            disable += "CoroutineCreationDuringComposition"
        }
    }
}
