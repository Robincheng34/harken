// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.api

import com.rc.harken.net.client.NeteaseClient
import com.rc.harken.net.client.NeteaseJson
import com.rc.harken.net.client.toJsonElement
import com.rc.harken.net.model.AlbumDetailResponse
import com.rc.harken.net.model.AlbumDynamicResponse
import com.rc.harken.net.model.AlbumSummary
import com.rc.harken.net.model.ArtistAlbumsResponse
import com.rc.harken.net.model.ArtistResponse
import com.rc.harken.net.model.ArtistSummary
import com.rc.harken.net.model.CloudResponse
import com.rc.harken.net.model.HighQualityResponse
import com.rc.harken.net.model.HotSearchDetailResponse
import com.rc.harken.net.model.HotSearchItem
import com.rc.harken.net.model.LyricResponse
import com.rc.harken.net.model.NewAlbumsResponse
import com.rc.harken.net.model.PlayRecordItem
import com.rc.harken.net.model.PlayRecordResponse
import com.rc.harken.net.model.PlaylistBriefBody
import com.rc.harken.net.model.PlaylistDetailResponse
import com.rc.harken.net.model.PlaylistSummary
import com.rc.harken.net.model.SearchResponse
import com.rc.harken.net.model.SearchResult
import com.rc.harken.net.model.SearchSuggestBody
import com.rc.harken.net.model.SearchSuggestResponse
import com.rc.harken.net.model.SongDetailResponse
import com.rc.harken.net.model.SongURLData
import com.rc.harken.net.model.SongURLResponse
import com.rc.harken.net.model.TopPlaylistResponse
import com.rc.harken.net.model.ToplistArtistResponse
import com.rc.harken.net.model.ToplistItem
import com.rc.harken.net.model.ToplistResponse
import com.rc.harken.net.model.Track
import com.rc.harken.net.model.UserPlaylistsResponse
import com.rc.harken.net.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Typed NetEase Cloud Music API surface, mapped to real weapi/eapi endpoints.
 *
 * Ported 1:1 from `NeteaseAPI.swift`. Open class: tests subclass to override
 * single methods with fakes.
 */
open class NeteaseApi(private val client: NeteaseClient) {

    // MARK: - Request helpers

    private suspend inline fun <reified T> weapi(
        path: String,
        payload: Map<String, Any?> = emptyMap(),
    ): T {
        val raw = client.weapi(path, payload)
        return decodeChecked(raw, serializer())
    }

    private suspend inline fun <reified T> eapi(
        path: String,
        payload: Map<String, Any?> = emptyMap(),
    ): T {
        val raw = client.eapi(path, payload)
        return decodeChecked(raw, serializer())
    }

    /**
     * Decodes the body, surfacing business-level errors (iOS `decoded`).
     *
     * 原 bug：两次 parse/decode 都跑在调用方（Main.immediate）调度器上，错误路径
     * businessCode/businessMessage 还各解析一次大 body。现整体移入
     * [Dispatchers.Default]，code/message 复用同一个已解析的 [JsonElement]。
     *
     * 只解析一次：**为什么** —— 旧实现先用 `parseToJsonElement(raw)` 解析一遍取业务码，
     * 再用 `decodeFromString(serializer, raw)` 把**同一个字符串**重新解析一遍得到目标
     * 类型，首页/歌单这类数百 KB 的响应因此被完整反序列化两次、对象图复制两份（纯
     * CPU + 分配浪费）。现在改成「一次树解析 + 对该树做解码」：树只建一次，业务码
     * 校验与目标类型解码共用它。等价性依据：`decodeFromJsonElement` 走
     * `JsonTreeDecoder`，与 `decodeFromString` 的 `StreamingJsonDecoder` 共用同一个
     * [NeteaseJson] 配置（`ignoreUnknownKeys` / `coerceInputValues` 在树解码器里同样
     * 实现），抛出的仍是 `SerializationException` / `IllegalArgumentException` 家族，
     * 因此异常类型与 [NeteaseApiError.Decoding] 映射不变；响应体无法解析时旧实现
     * 靠随后的 `decodeFromString` 抛错，这里显式抛同一映射。
     */
    private suspend fun <T> decodeChecked(raw: String, serializer: KSerializer<T>): T =
        withContext(Dispatchers.Default) {
            val element = try {
                NeteaseJson.parseToJsonElement(raw)
            } catch (e: SerializationException) {
                throw NeteaseApiError.Decoding(e.message ?: e.toString())
            } catch (e: IllegalArgumentException) {
                throw NeteaseApiError.Decoding(e.message ?: e.toString())
            }
            assertBusinessCode(element)
            try {
                NeteaseJson.decodeFromJsonElement(serializer, element)
            } catch (e: SerializationException) {
                throw NeteaseApiError.Decoding(e.message ?: e.toString())
            } catch (e: IllegalArgumentException) {
                throw NeteaseApiError.Decoding(e.message ?: e.toString())
            }
        }

    /** 解析响应体为 JSON；不可解析时返回 null（后续按解码失败处理）。 */
    private fun parseJsonElement(raw: String): JsonElement? = try {
        NeteaseJson.parseToJsonElement(raw)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 响应体顶层 `code` 字段；null 表示缺失或不可解析。 */
    private fun businessCodeOf(element: JsonElement?): Int? =
        (element as? JsonObject)?.get("code")?.let { code ->
            (code as? JsonPrimitive)?.content?.trim()?.toIntOrNull()
        }

    private fun businessMessageOf(element: JsonElement?): String? {
        val obj = element as? JsonObject ?: return null
        val message = obj["message"] ?: obj["msg"] ?: return null
        return (message as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** 业务码校验：200 放行；301 → NeedLogin；其余 → Business（与 `assertCode` 等价）。 */
    private fun assertBusinessCode(element: JsonElement?) {
        val code = businessCodeOf(element) ?: return
        if (code == NeteaseClient.BUSINESS_CODE_OK) return
        if (code == NeteaseClient.BUSINESS_CODE_NEED_LOGIN) throw NeteaseApiError.NeedLogin
        throw NeteaseApiError.Business(code, businessMessageOf(element))
    }

    // MARK: - Auth

    /** Result of a QR-login poll. Codes: 800 expired · 801 waiting · 802 scanned · 803 success. */
    @kotlinx.serialization.Serializable
    data class QRCheckResult(
        val code: Int,
        val message: String? = null,
        val nickname: String? = null,
        val avatarUrl: String? = null,
    )

    // open：测试（LoginViewModelTest 等）以匿名子类覆写注入确定性假数据。
    open suspend fun qrKey(): String {
        val response: QRKeyResponse = weapi("/login/qrcode/unikey", mapOf("type" to 1))
        // 空 unikey 必须当失败处理：否则会渲染出 `...?codekey=` 的空二维码。
        // 官方客户端判断一个二维码是不是登录码时要求 codekey **非空**
        // （反编译产物 module/barcode/g.java:221 `getQueryParameter("codekey")`
        // 后走 `utils/fs.g` 即 isNotEmpty），空值会被手机端静默丢弃——用户看到
        // 的就是「扫了完全没反应」；我们这边轮询随后拿到 code=400，若再被忽略
        // 就彻底没有反馈。
        if (response.unikey.isBlank()) {
            throw NeteaseApiError.Business(response.code, "二维码获取失败，请重试")
        }
        return response.unikey
    }

    /** The login URL rendered as a QR code. */
    fun qrLoginURL(unikey: String): String = "https://music.163.com/login?codekey=$unikey"

    /**
     * Polls the QR login. On 803 the auth cookies arrive via Set-Cookie and
     * are absorbed by the client.
     */
    open suspend fun qrCheck(unikey: String): QRCheckResult {
        val raw = client.weapi(
            "/login/qrcode/client/login",
            mapOf("key" to unikey, "type" to 1),
        )
        return try {
            NeteaseJson.decodeFromString<QRCheckResult>(raw)
        } catch (e: SerializationException) {
            throw NeteaseApiError.Decoding(e.message ?: e.toString())
        } catch (e: IllegalArgumentException) {
            throw NeteaseApiError.Decoding(e.message ?: e.toString())
        }
    }

    // open：测试（LoginViewModelTest 等）以匿名子类覆写注入确定性假数据。
    open suspend fun sendSMSCode(phone: String, countryCode: String = "86") {
        // 忠于反编译产物 tf0/i.java:766-784：官方 sms/captcha/sent 只收
        // cellphone + ctcode 两个参数，无 secrete；旧实现误用 phone 导致
        // 服务端缺必填参数返回 code=400「参数错误」。
        val response: CodeOnly = weapi(
            "/sms/captcha/sent",
            mapOf(
                "cellphone" to phone,
                "ctcode" to countryCode,
            ),
        )
        assertCode(response.code)
    }

    /** Phone-number login with an SMS code; auth cookies arrive via Set-Cookie. */
    open suspend fun loginCellphone(phone: String, captcha: String, countryCode: String = "86") {
        val response: CodeOnly = weapi(
            "/login/cellphone",
            mapOf(
                "phone" to phone,
                "countrycode" to countryCode,
                "captcha" to captcha,
                "rememberLogin" to "true",
            ),
        )
        assertCode(response.code)
        if (!client.isLoggedIn) {
            throw NeteaseApiError.Business(-1, "登录失败，请重试")
        }
    }

    /** Logs out; with [detachedCookies] the caller's jar is left untouched. */
    open suspend fun logout(detachedCookies: Map<String, String>? = null) {
        val oldCookies = detachedCookies ?: client.authenticationCookies()
        // 顺序很重要：**先请求、成功后才丢本地凭证**。
        //
        // 原实现是「先 clearAuthCookies() 再发请求，并把所有异常吞掉」，带来两个后果：
        // 1. 调用方（设置页）永远看到「成功」，无法区分断网/服务端失败；
        // 2. 一旦断网，本地凭证其实已经被清掉，但界面若按「失败保留登录态」处理，
        //    两边状态就互相矛盾（本地已登出、UI 说还登着）。
        // 现在失败会原样抛出，由调用方提示并保留登录态；只有服务端确认登出才清凭证。
        // 请求本身用 cookieOverrides 携带旧 Cookie，不依赖本地 jar 是否还在。
        var failure: Throwable? = null
        try {
            client.weapi(
                "/logout",
                cookieOverrides = oldCookies,
                absorbResponseCookies = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        }
        if (failure != null) throw failure
        // 服务端已确认登出，才丢弃本地认证凭证（非认证 Cookie 如 os/appver 保留）。
        if (detachedCookies == null) client.clearAuthCookies()
    }

    /** Renews the login token; best-effort. */
    open suspend fun refreshLogin() {
        try {
            client.weapi("/login/token/refresh")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed refresh is not fatal; the next request decides.
        }
    }

    // open：短信登录成功后由 LoginViewModel 覆写注入假档案。
    open suspend fun userAccount(): UserProfile? {
        val response: AccountResponse = weapi("/w/nuser/account/get")
        return response.profile
    }

    // MARK: - User library

    open suspend fun userPlaylists(uid: Long, limit: Int = 2000, offset: Int = 0): List<PlaylistSummary> {
        val response: UserPlaylistsResponse = weapi(
            "/user/playlist",
            mapOf("uid" to uid, "limit" to limit, "offset" to offset, "includeVideo" to true),
        )
        return response.playlist
    }

    open suspend fun likedTrackIDs(uid: Long): List<Long> {
        val response: LikelistResponse = weapi("/song/like/get", mapOf("uid" to uid))
        return response.ids
    }

    /**
     * 收藏 / 取消收藏歌曲（红心）。
     *
     * 端点与字段对齐官方 `NeteaseMusicApiImpl.java:24033-24082` 的 `v1/radio/like`
     * （请求体 `trackId`/`time`/`alg`/`like`）。
     *
     * 官方该方法还带 `rqRefer`/`songRqRefer`/`mode`/`subMode`/`source`/`checkToken`
     * 等上下文字段，但它们在反编译产物中是**动态值或可空值**（`alg` 甚至来自方法
     * 入参而非字面量），无法确定单曲红心时的取值，故**不猜测填充**。
     *
     * 实测（本机 OnePlus7TPro，登录态）：请求正常发出但被服务端拒绝，
     * 返回 `code=524`「当前环境异常，已取消喜欢」；同账号在官方客户端
     * （Redmi K30 Pro，同一账号）收藏/取消收藏均正常。判定为本客户端请求
     * 与官方存在差异触发风控，非判定逻辑问题——故仍用标准 `assertCode`，
     * 不放宽成功码（502 在官方语义里是「已取消喜欢」，非成功）。
     */
    open suspend fun likeTrack(id: Long, like: Boolean) {
        val response: CodeOnly = weapi(
            "/radio/like?alg=itembased&trackId=$id&time=3",
            mapOf("trackId" to id, "like" to like),
        )
        assertCode(response.code, "操作失败，专辑下架或版权锁定")
    }

    open suspend fun likedAlbums(limit: Int = 500, offset: Int = 0): List<AlbumSummary> {
        val response: SublistResponse<AlbumSummary> = weapi(
            "/album/sublist",
            mapOf("limit" to limit, "offset" to offset, "total" to true),
        )
        return response.data
    }

    open suspend fun likedArtists(limit: Int = 500, offset: Int = 0): List<ArtistSummary> {
        val response: SublistResponse<ArtistSummary> = weapi(
            "/artist/sublist",
            mapOf("limit" to limit, "offset" to offset, "total" to true),
        )
        return response.data
    }

    open suspend fun playRecords(uid: Long, week: Boolean): List<PlayRecordItem> {
        val response: PlayRecordResponse = weapi(
            "/v1/play/record",
            mapOf("uid" to uid, "type" to if (week) 1 else 0),
        )
        return (if (week) response.weekData else response.allData) ?: emptyList()
    }

    /**
     * 删除单条播放记录。
     *
     * 自编接口：官方未提供删除播放记录的接口（反编译产物与现有 NeteaseApi 均只有读取），
     * 按用户授权自行定义端点 `/v1/play/record/delete`。服务端若不支持会返回非 200 code
     * 或结构不符，调用方统一用 `runCatching` 忽略失败（页面已做乐观删除）。
     */
    open suspend fun deletePlayRecord(songId: Long) {
        val response: CodeOnly = weapi("/v1/play/record/delete", mapOf("songId" to songId))
        assertCode(response.code)
    }

    /**
     * 清空全部播放记录。
     *
     * 自编接口：同 [deletePlayRecord]，端点 `/v1/play/record/clear`，失败由调用方忽略。
     */
    open suspend fun clearPlayRecords() {
        val response: CodeOnly = weapi("/v1/play/record/clear")
        assertCode(response.code)
    }

    open suspend fun cloudSongs(limit: Int = 1000, offset: Int = 0): CloudResponse =
        weapi("/v1/cloud/get", mapOf("limit" to limit, "offset" to offset))

    // MARK: - Playlists

    open suspend fun personalizedPlaylists(limit: Int = 30): List<PlaylistSummary> {
        val response: PersonalizedResponse = weapi(
            "/personalized/playlist",
            mapOf("limit" to limit, "total" to true, "n" to 1000),
        )
        return response.result
    }

    /** Logged-in daily recommended playlists. */
    open suspend fun recommendResource(): List<PlaylistSummary> {
        val response: RecommendResourceResponse = weapi("/v1/discovery/recommend/resource")
        return response.recommend
    }

    open suspend fun dailyRecommendSongs(): List<Track> {
        val response: RecommendSongsResponse = weapi("/v3/discovery/recommend/songs")
        return response.data?.dailySongs ?: emptyList()
    }

    open suspend fun playlistDetail(id: Long): PlaylistDetailResponse =
        weapi("/v6/playlist/detail", mapOf("id" to id, "n" to 100_000, "s" to 8))

    /** Lightweight name + cover fetch. */
    open suspend fun playlistBrief(id: Long): PlaylistBriefBody {
        val response: PlaylistBriefResponse = weapi(
            "/v6/playlist/detail",
            mapOf("id" to id, "n" to 1, "s" to 0),
        )
        return response.playlist
    }

    open suspend fun songDetails(ids: List<Long>): SongDetailResponse {
        if (ids.isEmpty()) return SongDetailResponse(songs = emptyList(), privileges = emptyList())
        val c = ids.joinToString(prefix = "[", postfix = "]", separator = ",") { "{\"id\":$it}" }
        return weapi("/v3/song/detail", mapOf("c" to c))
    }

    open suspend fun topPlaylists(
        category: String,
        order: String = "hot",
        limit: Int = 50,
        offset: Int = 0,
    ): TopPlaylistResponse =
        weapi(
            "/playlist/list",
            mapOf(
                "cat" to category,
                "order" to order,
                "limit" to limit,
                "offset" to offset,
                "total" to true,
            ),
        )

    open suspend fun highQualityPlaylists(
        category: String = "全部",
        limit: Int = 50,
        before: Long = 0,
    ): HighQualityResponse =
        weapi(
            "/playlist/highquality/list",
            mapOf("cat" to category, "limit" to limit, "lasttime" to before, "total" to true),
        )

    open suspend fun toplists(): List<ToplistItem> {
        val response: ToplistResponse = eapi("/toplist")
        return response.list
    }

    open suspend fun createPlaylist(name: String, isPrivate: Boolean): Long? {
        val response: PlaylistCreateResponse = weapi(
            "/playlist/create",
            mapOf("name" to name, "privacy" to if (isPrivate) 10 else 0, "type" to "NORMAL"),
        )
        return response.id
    }

    open suspend fun subscribePlaylist(id: Long, subscribe: Boolean) {
        val response: CodeOnly = weapi(
            "/playlist/${if (subscribe) "subscribe" else "unsubscribe"}",
            mapOf("id" to id),
        )
        assertCode(response.code)
    }

    /**
     * Adds/removes tracks on a playlist. Code 512 (already-in-playlist quirk)
     * retries with doubled ids, like the reference implementation.
     */
    open suspend fun playlistTracks(op: String, playlistID: Long, trackIDs: List<Long>) {
        val ids = trackIDs.joinToString(prefix = "[", postfix = "]", separator = ",")
        val raw = client.weapi(
            "/playlist/manipulate/tracks",
            mapOf("op" to op, "pid" to playlistID, "trackIds" to ids, "imme" to "true"),
        )
        val element = parseJsonElement(raw)
        val code = businessCodeOf(element)
        if (code != null && code != NeteaseClient.BUSINESS_CODE_OK) {
            if (code == 512 && op == "add") {
                val doubled = (trackIDs + trackIDs)
                    .joinToString(prefix = "[", postfix = "]", separator = ",")
                val retryRaw = client.weapi(
                    "/playlist/manipulate/tracks",
                    mapOf("op" to op, "pid" to playlistID, "trackIds" to doubled, "imme" to "true"),
                )
                // 原 bug：重试响应被直接丢弃，即使重试失败也当成功返回。
                // 重试响应同样走既有业务码校验，失败抛 NeteaseApiError。
                assertBusinessCode(parseJsonElement(retryRaw))
                return
            }
            throw NeteaseApiError.Business(code, businessMessageOf(element))
        }
    }

    /** 心动模式 — generates a heartbeat-mode queue from a seed song in a playlist. */
    open suspend fun intelligenceList(songID: Long, playlistID: Long): List<Track> {
        val response: IntelligenceResponse = weapi(
            "/playmode/intelligence/list",
            mapOf(
                "songId" to songID,
                "type" to "fromPlayOne",
                "playlistId" to playlistID,
                "startMusicId" to songID,
                "count" to 1,
            ),
        )
        return response.data.orEmpty().mapNotNull { it.songInfo }
    }

    // MARK: - Tracks

    open suspend fun songURL(ids: List<Long>, level: String): List<SongURLData> {
        val idString = ids.joinToString(prefix = "[", postfix = "]", separator = ",")
        val payload = LinkedHashMap<String, Any?>(
            mapOf("ids" to idString, "level" to level, "encodeType" to "flac"),
        )
        if (level == "sky") payload["immerseType"] = "c51"
        val response: SongURLResponse = eapi("/song/enhance/player/url/v1", payload)
        return response.data
    }

    /** `/song/lyric/v1` first (it also carries verbatim `yrc`), classic fallback. */
    open suspend fun lyric(id: Long): LyricResponse {
        val v1 = try {
            weapi<LyricResponse>(
                "/song/lyric/v1",
                mapOf(
                    "id" to id,
                    "cp" to false,
                    "lv" to 0,
                    "kv" to 0,
                    "tv" to 0,
                    "rv" to 0,
                    "yv" to 0,
                    "ytv" to 0,
                    "yrv" to 0,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (v1 != null && (!v1.lrc?.lyric.isNullOrEmpty() || !v1.yrc?.lyric.isNullOrEmpty())) {
            return v1
        }
        return weapi(
            "/song/lyric",
            mapOf("id" to id, "lv" to -1, "kv" to -1, "tv" to -1, "rv" to -1),
        )
    }

    open suspend fun personalFM(): List<Track> {
        val response: FMResponse = weapi("/v1/radio/get")
        return response.data ?: emptyList()
    }

    open suspend fun fmTrash(id: Long) {
        val response: CodeOnly = weapi(
            "/radio/trash/add?alg=RT&songId=$id&time=25",
            mapOf("songId" to id),
        )
        assertCode(response.code)
    }

    // MARK: - Search

    open suspend fun search(
        keywords: String,
        type: Int,
        limit: Int = 30,
        offset: Int = 0,
    ): SearchResult {
        val response: SearchResponse = eapi(
            "/cloudsearch/pc",
            mapOf(
                "s" to keywords,
                "type" to type,
                "limit" to limit,
                "offset" to offset,
                "total" to true,
            ),
        )
        return response.result ?: SearchResult()
    }

    open suspend fun searchSuggest(keywords: String): SearchSuggestBody? {
        val response: SearchSuggestResponse = weapi("/search/suggest/web", mapOf("s" to keywords))
        return response.result
    }

    /** 热搜榜（官方搜索落地页，实际 weapi 端点为 `/weapi/hotsearchlist/get`）。 */
    open suspend fun hotSearchDetail(): List<HotSearchItem> {
        val response: HotSearchDetailResponse = weapi("/hotsearchlist/get", emptyMap())
        return response.data
    }

    // MARK: - Albums

    open suspend fun album(id: Long): AlbumDetailResponse = weapi("/v1/album/$id")

    open suspend fun newAlbums(area: String = "ALL", limit: Int = 30, offset: Int = 0): List<AlbumSummary> {
        val response: NewAlbumsResponse = weapi(
            "/album/new",
            mapOf("area" to area, "limit" to limit, "offset" to offset, "total" to true),
        )
        return response.albums
    }

    open suspend fun subscribeAlbum(id: Long, subscribe: Boolean) {
        val response: CodeOnly = weapi(
            "/album/${if (subscribe) "sub" else "unsub"}",
            mapOf("id" to id),
        )
        assertCode(response.code)
    }

    // MARK: - Artists

    open suspend fun artist(id: Long): ArtistResponse = weapi("/v1/artist/$id")

    open suspend fun artistAlbums(id: Long, limit: Int = 100, offset: Int = 0): List<AlbumSummary> {
        val response: ArtistAlbumsResponse = weapi(
            "/artist/albums/$id",
            mapOf("limit" to limit, "offset" to offset, "total" to true),
        )
        return response.hotAlbums
    }

    open suspend fun subscribeArtist(id: Long, subscribe: Boolean) {
        val response: CodeOnly = weapi(
            "/artist/${if (subscribe) "sub" else "unsub"}",
            mapOf("artistId" to id, "artistIds" to "[$id]"),
        )
        assertCode(response.code)
    }

    open suspend fun topArtists(limit: Int = 100): List<ArtistSummary> {
        val response: ToplistArtistResponse = weapi(
            "/toplist/artist",
            mapOf("type" to 1, "limit" to limit, "offset" to 0, "total" to true),
        )
        return response.list.artists
    }

    // MARK: - Scrobbling (P1)

    /** `startplay` weblog — writes the song into the 最近播放 list. */
    open suspend fun scrobbleStart(trackID: Long, sourceID: Long) {
        sendWeblog(
            listOf(
                mapOf(
                    "action" to "startplay",
                    "json" to mapOf(
                        "id" to trackID,
                        "type" to "song",
                        "mainsite" to "1",
                        "mainsiteWeb" to "1",
                        "content" to "id=$sourceID",
                    ),
                ),
            ),
        )
    }

    /** `play` weblog — increments the listening-ranking play count and time. */
    open suspend fun scrobbleFinish(trackID: Long, sourceID: Long, seconds: Int) {
        sendWeblog(
            listOf(
                mapOf(
                    "action" to "play",
                    "json" to mapOf(
                        "download" to 0,
                        "end" to "playend",
                        "id" to trackID,
                        "sourceId" to sourceID.toString(),
                        "time" to seconds,
                        "type" to "song",
                        "wifi" to 0,
                        "source" to "list",
                        "mainsite" to "1",
                        "mainsiteWeb" to "1",
                        "content" to "id=$sourceID",
                    ),
                ),
            ),
        )
    }

    /** Routed via eapi with the desktop-client cookie (`os=osx`); best-effort. */
    private suspend fun sendWeblog(log: List<Map<String, Any?>>) {
        val logs = try {
            val array = buildJsonArray {
                for (entry in log) {
                    add(
                        buildJsonObject {
                            for ((key, value) in entry) {
                                put(key, value.toJsonElement())
                            }
                        },
                    )
                }
            }
            NeteaseJson.encodeToString(JsonArray.serializer(), array)
        } catch (e: Exception) {
            return
        }
        try {
            client.eapi(
                "/feedback/weblog",
                mapOf("logs" to logs),
                cookieOverrides = mapOf("os" to "osx"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Scrobbling must never interrupt playback.
        }
    }

    private fun assertCode(code: Int, message: String? = null) {
        if (code != NeteaseClient.BUSINESS_CODE_OK) {
            throw NeteaseApiError.Business(code, message)
        }
    }

    // MARK: - Wire types (iOS response envelopes)

    @kotlinx.serialization.Serializable
    internal data class CodeOnly(val code: Int)

    @kotlinx.serialization.Serializable
    internal data class QRKeyResponse(val code: Int, val unikey: String)

    @kotlinx.serialization.Serializable
    internal data class AccountResponse(
        val code: Int,
        val profile: UserProfile? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class LikelistResponse(val ids: List<Long> = emptyList())

    @kotlinx.serialization.Serializable
    internal data class SublistResponse<Item>(
        val data: List<Item> = emptyList(),
        val count: Int? = null,
        val hasMore: Boolean? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class PersonalizedResponse(val result: List<PlaylistSummary> = emptyList())

    @kotlinx.serialization.Serializable
    internal data class RecommendResourceResponse(
        val recommend: List<PlaylistSummary> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    internal data class RecommendSongsResponse(
        val data: Body? = null,
    ) {
        @kotlinx.serialization.Serializable
        data class Body(val dailySongs: List<Track>? = null)
    }

    @kotlinx.serialization.Serializable
    internal data class PlaylistBriefResponse(val playlist: PlaylistBriefBody)

    @kotlinx.serialization.Serializable
    internal data class PlaylistCreateResponse(
        val code: Int,
        val id: Long? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class IntelligenceResponse(
        val data: List<Item>? = null,
    ) {
        @kotlinx.serialization.Serializable
        data class Item(val songInfo: Track? = null, val id: Long? = null)
    }

    @kotlinx.serialization.Serializable
    internal data class FMResponse(val data: List<Track>? = null)

    @kotlinx.serialization.Serializable
    internal data class PersonalizedNewsongResponse(
        val result: List<Item> = emptyList(),
    ) {
        @kotlinx.serialization.Serializable
        data class Item(
            val id: Long,
            val name: String? = null,
            val song: Track? = null,
        )
    }
}
