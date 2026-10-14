// Ported from kumone by missuo (LGPL-3.0)
//
// 我的页 ViewModel（T24 `LibraryViewModel.kt`）。
//
// 按 iOS `SidebarView` + `AccountStore` 语义：登录后取 `userAccount` 与
// `userPlaylists(uid)`，按「我创建的（specialType != 5）/ 我收藏的（subscribed）」拆分；
// 未登录不取数。纯 Kotlin + 协程，可纯 JVM 单测。

package im.missuo.kumone.feature.library

import im.missuo.kumone.net.api.NeteaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val api: NeteaseApi,
    private val isLoggedIn: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _uiState: MutableStateFlow<LibraryUiState> = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** 加载我的页（登录态变化时重新调用）。 */
    fun load() {
        loadJob?.cancel()
        val loggedIn = isLoggedIn()
        if (!loggedIn) {
            _uiState.value = LibraryUiState(isLoggedIn = false)
            return
        }
        loadJob = scope.launch {
            _uiState.update { it.copy(isLoggedIn = true, isLoading = true, error = null) }

            val profile = runCatching { api.userAccount() }.getOrNull()
            if (profile == null) {
                _uiState.update { it.copy(isLoading = false, error = ERROR_LOAD) }
                return@launch
            }

            val uid = profile.userId
            val playlists = runCatching { api.userPlaylists(uid) }.getOrNull().orEmpty()
            // iOS `AccountStore`：按「创建者是否为本人」拆分，而非 subscribed 标记
            //   createdPlaylists    = creator.userId == uid && !isLikedSongsList
            //   subscribedPlaylists = creator.userId != uid
            val created = playlists.filter {
                it.creator?.userId == uid && it.specialType != LIKED_SONGS_SPECIAL_TYPE
            }
            val subscribed = playlists.filter { it.creator?.userId != uid }

            _uiState.update {
                it.copy(
                    isLoggedIn = true,
                    isLoading = false,
                    nickname = profile.nickname,
                    avatarUrl = profile.avatarUrl,
                    createdPlaylists = created,
                    subscribedPlaylists = subscribed,
                )
            }
        }
    }

    /** 创建歌单（iOS `createPlaylist`），成功后重新加载。 */
    fun createPlaylist(name: String, isPrivate: Boolean) {
        scope.launch {
            val id = runCatching { api.createPlaylist(name, isPrivate) }.getOrNull()
            if (id != null) load()
        }
    }

    /** 取消内部协程（页面销毁时由 UI 调用，避免收集器常驻泄漏）。 */
    fun cancel() {
        scope.cancel()
    }

    private companion object {
        const val ERROR_LOAD = "加载失败"
        /** iOS: `Playlist.specialType == 5` 即「我喜欢的音乐」。 */
        const val LIKED_SONGS_SPECIAL_TYPE = 5
    }
}
