// Ported from kumone by missuo (LGPL-3.0)
//
// 进程级依赖图（架构文档 §4.1 `AppGraph` 1:1 移植）。
//
// 所有核心依赖在此装配为单例：DataStore → CookieStore / SettingsRepository →
// NeteaseClient → NeteaseApi；播放控制器与 Media3 引擎经 [MusicController] 桥接。
// [bootstrap] 负责冷启动恢复登录态（读 Cookie → refreshLogin → userAccount）。

package im.missuo.kumone.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import im.missuo.kumone.net.api.NeteaseApi
import im.missuo.kumone.net.api.NeteaseApiError
import im.missuo.kumone.net.client.CookieStore
import im.missuo.kumone.net.client.NeteaseClient
import im.missuo.kumone.net.model.UserProfile
import im.missuo.kumone.net.update.UpdateChecker
import im.missuo.kumone.feature.library.PlayRecordHideStore
import im.missuo.kumone.player.MusicController
import im.missuo.kumone.player.PlaybackCommands
import im.missuo.kumone.player.PlayerDependencies
import im.missuo.kumone.storage.cookies.DataStoreCookieStore
import im.missuo.kumone.storage.db.KumoneDatabase
import im.missuo.kumone.storage.download.DownloadRepository
import im.missuo.kumone.storage.playrecord.RecentPlayHideRepository
import im.missuo.kumone.storage.search.SearchHistoryRepository
import im.missuo.kumone.storage.settings.SettingsRepository
import im.missuo.kumone.storage.update.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 登录态：null = 未登录；持登录用户档案。 */
data class LoginState(val profile: UserProfile)

/**
 * Kumone 进程级依赖容器。
 */
class AppGraph private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val dataStore: DataStore<Preferences> =
        appContext.dataStore

    val cookieStore: DataStoreCookieStore = DataStoreCookieStore(dataStore)
    val settings: SettingsRepository = SettingsRepository(dataStore)
    val searchHistory: SearchHistoryRepository = SearchHistoryRepository(dataStore)
    val neteaseClient: NeteaseClient = NeteaseClient(cookieStore)
    val neteaseApi: NeteaseApi = NeteaseApi(neteaseClient)

    // 下载仓储（离线播放闭环）：本地 Room 清单 + 落盘文件，并作为 LocalSongSource
    // 注入播放器解析链。须在 PlayerDependencies.provide 之前实例化（provide 引用它）。
    val database: KumoneDatabase = KumoneDatabase.build(appContext)
    val downloadRepository: DownloadRepository = DownloadRepository(appContext, neteaseApi, database)

    // 在线检测更新：清单拉取器 + 仓储（下载/安装/待更新持久化）。
    val updateChecker: UpdateChecker = UpdateChecker()
    val updateRepository: UpdateRepository = UpdateRepository(appContext, updateChecker, dataStore)

    // `:core:player` 不能依赖 `:app`，故播放引擎的依赖经进程级 holder 注入
    // （PlayerService.kt 的 `PlayerDependencies`）。必须在构造 MusicController
    // 之前完成：MusicController.<init> → PlaybackEngine.acquire() 会 check
    // isProvided，否则冷启动即 FATAL（真机实测：KumoneApplication.onCreate 崩溃）。
    init {
        PlayerDependencies.provide(
            api = neteaseApi,
            settings = settings,
            scope = scope,
            localSource = downloadRepository,
        )
        // 「最近播放」本地删除标记落盘：官方删除接口不生效，删除只能本地隐藏，
        // 键池必须持久化，否则重启后已删记录全部回弹（用户反馈的 bug）。
        PlayRecordHideStore.attach(RecentPlayHideRepository(dataStore))
    }

    val musicController: MusicController = MusicController(appContext)

    /** 「取数 → 成队列 → 播放」编排（收口各 feature 的重复播放逻辑）。 */
    val playbackCommands: PlaybackCommands = PlaybackCommands(neteaseApi, musicController)

    private val _loginState: MutableStateFlow<LoginState?> = MutableStateFlow(null)
    val loginState: StateFlow<LoginState?> = _loginState.asStateFlow()

    /** 登录态布尔投影（供 feature 判断是否拉取登录专属内容）。 */
    private val _isLoggedIn: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    /** 冷启动恢复登录态。 */
    suspend fun bootstrap() {
        cookieStore.load()
        if (neteaseClient.isLoggedIn) {
            runCatching { neteaseApi.refreshLogin() }
            try {
                val profile = neteaseApi.userAccount()
                // 服务端明确应答（200 但无档案）= 未登录。
                _loginState.value = profile?.let { LoginState(it) }
                _isLoggedIn.value = profile != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeteaseApiError.NeedLogin) {
                // API 明确返回未登录业务码（/w/nuser/account/get 的 301）→ 凭证真失效。
                _loginState.value = null
                _isLoggedIn.value = false
            } catch (e: Exception) {
                // 原 bug：冷启动一次网络失败即把登录态整个进程周期置为未登录，
                // 而 MUSIC_U 仍在，状态自相矛盾且无补偿。网络失败 ≠ 凭证失效：
                // 保持 cookie 侧 isLoggedIn=true 的语义，并安排一次延迟补偿重试。
                _isLoggedIn.value = true
                scheduleLoginStateRetry()
            }
        }
    }

    /**
     * 在线检测更新（best-effort）：距上次检查满 24h 才拉取 version.json，
     * 若远端版本号更高则持久化为「待更新」。异常一律吞掉，不影响启动。
     */
    suspend fun checkForUpdateIfDue() {
        runCatching {
            val last = updateRepository.getLastCheckMillis()
            val now = System.currentTimeMillis()
            if (now - last < 24L * 60 * 60 * 1000) return@runCatching
            updateRepository.setLastCheckMillis(now)
            val manifest = updateRepository.fetchManifest()
            val installed = updateRepository.getInstalledVersion()
            if (manifest.versionCode > installed.versionCode) {
                updateRepository.savePendingUpdate(manifest)
            }
        }
    }

    /** 网络失败后的登录态补偿重试；仅当服务端明确应答时才翻转状态。 */
    private fun scheduleLoginStateRetry() {
        scope.launch {
            delay(LOGIN_RETRY_DELAY_MS)
            try {
                val profile = neteaseApi.userAccount()
                _loginState.value = profile?.let { LoginState(it) }
                _isLoggedIn.value = profile != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeteaseApiError.NeedLogin) {
                _loginState.value = null
                _isLoggedIn.value = false
            } catch (e: Exception) {
                // 二次补偿仍失败：维持当前（已登录）语义，避免再次误判。
            }
        }
    }

    /** 登录成功后更新登录态（由登录流程调用）。 */
    fun onLoggedIn(profile: UserProfile) {
        _loginState.value = LoginState(profile)
        _isLoggedIn.value = true
    }

    /** 登出后清空登录态。 */
    fun onLoggedOut() {
        _loginState.value = null
        _isLoggedIn.value = false
    }

    companion object {
        // 原 bug：双检锁的 instance 缺 @Volatile，存在读到半初始化对象的风险。
        @Volatile
        private var instance: AppGraph? = null

        private const val LOGIN_RETRY_DELAY_MS: Long = 30_000L

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}

/** 应用级偏好 DataStore（Cookies + 设置共用，分键存储）。 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "kumone.preferences",
)
