# Harken 倾耳 (com.rc.harken)

网易云音乐第三方客户端 — iOS 应用（Swift 6.2 + SwiftUI，LGPL-3.0，上游见根目录 [NOTICE](NOTICE)）的 Android 移植版。

- **包名**：`com.rc.harken` ｜ minSdk 26 / targetSdk 36 ｜ 分发：侧载 APK + GitHub Releases
- **架构**：单 Activity + 多模块 + MVVM；协议层（weapi/eapi 加密、约 50 个端点、模型解析、歌词解析）与播放队列状态机为**纯 Kotlin/JVM 模块**（`:core:net`、`:core:playback`），不依赖 Android framework
- **技术栈**：Jetpack Compose (Material3) + Media3 ExoPlayer + OkHttp + kotlinx.serialization + Room + DataStore；设计文档见 `docs/harken-android-architecture.md`

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
harken-android/
├── core/                  # 协议与播放内核
│   ├── net/               # 纯 JVM：weapi/eapi 加密、模型、API、歌词解析
│   ├── playback/          # 纯 JVM：队列状态机（RepeatMode/shuffle/FM/插播）
│   ├── storage/           # Android：DataStore 设置与 Cookie、Room 缓存
│   └── player/            # Android：Media3 引擎、前台服务、音质回落与上报
├── design-system/         # 主题 Token、基础组件、曲目行、封面取色、共享路由与 CompositionLocal
├── feature/               # login / home / explore / search / detail / fm / library / player / settings
└── app/                   # DI（AppGraph）、导航框架（HarkenApp / GlassTabBar）
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

### 已清零（v1.0.1）

- ~~`targetSdkVersion = 35`，未适配 Android 16 / API 36 的强制兼容行为~~
  → 已升到 **36**：全部 14 个模块的 `compileSdk` / `targetSdk` 统一为 36。
  App 原本就走 `enableEdgeToEdge()`，edge-to-edge 强制化无需额外改造；返回手势沿用
  Compose `BackHandler`（androidx.activity 会注册到 `OnBackInvokedDispatcher`），
  预测式返回打开后语义不变。

### 遗留（非阻塞，已知并接受）

- **Android 16 真机专项回归尚未做。** 升级到 targetSdk 36 后在 Android 11
  （HD1910 / API 30）上验证了安装、启动、设置页、离线降级与返回导航，但 API 36 的
  两项强制行为（边到边、预测式返回）只在 Android 16+ 上生效，需在 Android 16 设备
  复验。原目标机 vivo V2408A 当前不在线，恢复连接后补一轮。
- **应用内更新依赖 GitHub API 未认证额度**（60 次/小时/**出口 IP**，国内 CGNAT 下易被
  他人耗尽）。已加兜底：API 返回 403/429 时改走网页端 `releases/latest` 的 302 重定向，
  不消耗额度、不限流。该兜底按资产命名约定 `Harken-<version>.apk` 推导下载地址，
  **改资产命名必须同步改 `UpdateManifest.kt` 里的推导逻辑**。

## 版本与发版约定

- `versionCode = major * 10000 + minor * 100 + patch`，与 Git tag 一一对应
  （`v1.0.1` → `10001`）。应用内更新把 GitHub Release 的 `tag_name` 按同一规则换算后
  与本地 `versionCode` 比较，**两边必须用同一套编码**。
- 发布流程：改 `app/build.gradle.kts` 的 `versionCode` / `versionName` → 构建
  `:app:assembleRelease` → 打 tag → 建 Release 并上传 `Harken-<version>.apk` 与
  `SHA256SUMS.txt`（资产命名见上一条约束）。
- **debug 与 release 使用同一把发布证书**（仓库根 `harken-release.jks`，不入 git），
  因此两类包可互相覆盖安装；`keystore.properties` 缺失时自动回落到 AGP 默认调试证书。

## 合规

- 本项目基于上游 LGPL-3.0 项目移植，详见根目录 [`NOTICE`](NOTICE)。
- 灰色歌曲解锁功能默认关闭，并保留「客户书面确认后启用」的合规流程。
