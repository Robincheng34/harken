# Kumone 雲音 (im.missuo.kumone)

网易云音乐第三方客户端 — iOS 应用 [missuo/kumone](https://github.com/missuo/kumone)（Swift 6.2 + SwiftUI，LGPL-3.0）的 Android 移植版。

- **包名**：`im.missuo.kumone` ｜ minSdk 26 / targetSdk 35 ｜ 分发：侧载 APK + GitHub Releases
- **架构**：单 Activity + 多模块 + MVVM；协议层（weapi/eapi 加密、约 50 个端点、模型解析、歌词解析）与播放队列状态机为**纯 Kotlin/JVM 模块**（`:core:net`、`:core:playback`），不依赖 Android framework
- **技术栈**：Jetpack Compose (Material3) + Media3 ExoPlayer + OkHttp + kotlinx.serialization + Room + DataStore；设计文档见 `docs/kumone-android-architecture.md`

## 构建

要求 JDK 17+（推荐 JDK 21）与 Android SDK（platform 35、build-tools 35+）。

```bash
# 本地 SDK 位置（不入库）
echo 'sdk.dir=D\:\\Android\\Sdk' > local.properties    # Windows 示例

# 组装 debug APK
./gradlew :app:assembleDebug
```

## 测试（QA 命令）

协议层与队列状态机为纯 JVM 模块，**无 Android SDK 亦可运行**：

```bash
./gradlew :core:net:test :core:playback:test
```

`:core:net` 内含 weapi/eapi 加密金标向量测试（与 iOS 实现逐字节一致，基准存于
`core/net/src/test/resources/fixtures/`）。

feature 模块的 ViewModel 为纯 Kotlin + 协程，可在有 SDK 的机器上跑 JVM 单测：

```bash
./gradlew :feature:login:testDebugUnitTest :feature:home:testDebugUnitTest \
          :feature:explore:testDebugUnitTest :feature:search:testDebugUnitTest \
          :feature:detail:testDebugUnitTest :feature:fm:testDebugUnitTest \
          :feature:library:testDebugUnitTest
```

报告位于 `feature/*/build/test-results/testDebugUnitTest/`。

## 模块结构

```
kumone-android/
├── core/                  # 协议与播放内核
│   ├── net/               # 纯 JVM：weapi/eapi 加密、模型、API、歌词解析
│   ├── playback/          # 纯 JVM：队列状态机（RepeatMode/shuffle/FM/插播）
│   ├── storage/           # Android：DataStore 设置与 Cookie、Room 缓存
│   └── player/            # Android：Media3 引擎、前台服务、音质回落与上报
├── design-system/         # 主题 Token、基础组件、曲目行、封面取色、共享路由与 CompositionLocal
├── feature/               # login / home / explore / search / detail / fm / library / player / settings
└── app/                   # DI（AppGraph）、导航框架（KumoneApp / GlassTabBar）
```

依赖方向单向：`app → feature:* → design-system → core:*`，feature 模块**不得**反向依赖
`:app`；跨模块共享的路由常量与 CompositionLocal 下沉到 `:design-system`。

## 构建注意事项（Windows / Git Bash 实测坑）

以下三条是本地实测踩到并已确认的坑，QA 在 Windows 上复现过：

1. **`./gradlew clean` 会失败，勿与其它任务串在同一条命令里。**
   Windows 上 Kotlin 增量编译缓存目录（`kotlin/compileKotlin/cacheable`）被 Gradle
   守护进程占用，`Unable to delete directory ...` 导致 `clean` 失败并连带整条命令失败。
   这是 Gradle × Windows 的已知问题，**与代码无关**。清缓存的正确做法：

   ```bash
   ./gradlew --stop
   MSYS_NO_PATHCONV=1 taskkill -F -IM java.exe -T
   ```

   > Git Bash 必须加 `MSYS_NO_PATHCONV=1`，否则 `/F` 会被当成路径转换，命令失效。

2. **`./gradlew test` 不加 `--rerun-tasks` 会报 UP-TO-DATE 根本不执行。**
   验证测试是否真的跑了，务必带 `--rerun-tasks`（否则读到的是上次的 UP-TO-DATE 结果，
   容易误判为「测试通过」）：

   ```bash
   ./gradlew :core:net:test :core:playback:test :feature:search:testDebugUnitTest \
             --no-daemon --rerun-tasks
   ```

   真实结果以 `build/test-results/**/TEST-*.xml` 为准（`tests=` / `failures=` / `errors=`）。

3. **Android 单测结果目录不是 `test/`。**
   `feature/*` 的单测报告在 `feature/*/build/test-results/testDebugUnitTest/`，
   而 `:core:*`（纯 JVM）在 `core/*/build/test-results/test/`。
   注意 Android 模块会**同时**产出 `testReleaseUnitTest/`，总数统计时不要重复计入
   （同一套用例跑两遍）。

4. **JDK 的四段式 `java.version` 会让 Gradle 8.13 在配置期直接崩。**
   本机默认 `JAVA_HOME` 是 Temurin 25.0.4.1，其 `java.version` 是 **四段**
   （`25.0.4.1`），而 Gradle 8.13 内置的 Kotlin DSL 编译器用
   `org.jetbrains.kotlin...JavaVersion.parse` 解析它，只能吃三段式版本，于是**在任何
   任务执行之前**就抛出一条没有任何上下文的异常：

   ```
   * What went wrong:
   25.0.4.1
   ...
   Caused by: java.lang.IllegalArgumentException: 25.0.4.1
       at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse(JavaVersion.java:307)
   ```

   报错信息只有一个版本号，**极易误判成代码问题**。解决办法是把 Gradle 的 JVM 固定到
   JDK 21 并把 `java.version` 遮成三段式。这两项属于**机器环境**，写进用户级
   `~/.gradle/gradle.properties`，不进仓库：

   ```properties
   org.gradle.java.home=D:\\Java\\jdk-21.0.12.1+1
   org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8 -Djava.version=21.0.12
   ```

   > `GRADLE_OPTS=-Djava.version=21.0.12` 无效：该属性不会传给 forked 出来的
   > 单用途守护进程；必须走 `org.gradle.jvmargs`。

## 技术债

- **`targetSdkVersion = 35`，而目标真机是 Android 16 / API 36（vivo V2408A）。**
  Android 16 对 targetSdk 低于 36 的应用启用更严格的兼容行为（edge-to-edge 强制、
  预测式返回等），本版尚未适配。**v1.1 需评估升到 36**：改动面较大（全屏/沉浸布局、
  返回手势、权限模型），因此本轮刻意不动，先记录风险。

## 合规

- 本项目基于 [kumone](https://github.com/missuo/kumone) by missuo 移植，遵循 **LGPL-3.0**，详见根目录 [`NOTICE`](NOTICE)。
- 灰色歌曲解锁功能默认关闭，并保留「客户书面确认后启用」的合规流程。
