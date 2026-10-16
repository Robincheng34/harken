// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.api

import im.missuo.kumone.net.client.NeteaseClient
import im.missuo.kumone.net.client.NeteaseJson
import im.missuo.kumone.net.client.toJsonElement
import im.missuo.kumone.net.model.AlbumDetailResponse
import im.missuo.kumone.net.model.AlbumDynamicResponse
import im.missuo.kumone.net.model.AlbumSummary
import im.missuo.kumone.net.model.ArtistAlbumsResponse
import im.missuo.kumone.net.model.ArtistResponse
import im.missuo.kumone.net.model.ArtistSummary
import im.missuo.kumone.net.model.CloudResponse
import im.missuo.kumone.net.model.HighQualityResponse
import im.missuo.kumone.net.model.HotSearchDetailResponse
import im.missuo.kumone.net.model.HotSearchItem
import im.missuo.kumone.net.model.LyricResponse
import im.missuo.kumone.net.model.NewAlbumsResponse
import im.missuo.kumone.net.model.PlayRecordItem
import im.missuo.kumone.net.model.PlayRecordResponse
import im.missuo.kumone.net.model.PlaylistBriefBody
import im.missuo.kumone.net.model.PlaylistDetailResponse
import im.missuo.kumone.net.model.PlaylistSummary
import im.missuo.kumone.net.model.SearchResponse
import im.missuo.kumone.net.model.SearchResult
import im.missuo.kumone.net.model.SearchSuggestBody
import im.missuo.kumone.net.model.SearchSuggestResponse
import im.missuo.kumone.net.model.SongDetailResponse
import im.missuo.kumone.net.model.SongURLData
import im.missuo.kumone.net.model.SongURLResponse
import im.missuo.kumone.net.model.TopPlaylistResponse
import im.missuo.kumone.net.model.ToplistArtistResponse
import im.missuo.kumone.net.model.ToplistItem
import im.missuo.kumone.net.model.ToplistResponse
import im.missuo.kumone.net.model.Track
import im.missuo.kumone.net.model.UserPlaylistsResponse
import im.missuo.kumone.net.model.UserProfile
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
     */
    private suspend fun <T> decodeChecked(raw: String, serializer: KSerializer<T>): T =
        withContext(Dispatchers.Default) {
            val element = parseJsonElement(raw)
            assertBusinessCode(element)
            try {
                NeteaseJson.decodeFromString(serializer, raw)
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
        val response: CodeOnly = weapi(
            "/sms/captcha/sent",
            mapOf(
                "ctcode" to countryCode,
                "cellphone" to phone,
                "secrete" to "music_middleuser_pclogin",
            ),
        )
        assertCode(response.code)
    }

    /** Phone-number login with an SMS code; auth cookies arrive via Set-Cookie. */
    open suspend fun loginCellphone(phone: String, captcha: String, countryCode: String = "86") {
        val response: CodeOnly = weapi(
            "/w/login/cellphone",
            mapOf(
                "type" to "1",
                "https" to "true",
                "phone" to phone,
                "countrycode" to countryCode,
                "captcha" to captcha,
                "remember" to "true",
                "secureCaptcha" to "",
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
        if (detachedCookies == null) client.clearAuthCookies()
        try {
            client.weapi(
                "/logout",
                cookieOverrides = oldCookies,
                absorbResponseCookies = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort logout: the local credentials are already gone.
        }
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

    open suspend fun personalizedNewSongs(limit: Int = 10): List<Track> {
        val response: PersonalizedNewsongResponse = weapi(
            "/personalized/newsong",
            mapOf("type" to "recommend", "limit" to limit, "areaId" to 0),
        )
        return response.result.mapNotNull { it.song }
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

    open suspend fun deletePlaylist(id: Long) {
        val response: CodeOnly = weapi("/playlist/remove", mapOf("ids" to "[$id]"))
        assertCode(response.code)
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

    open suspend fun albumDynamic(id: Long): AlbumDynamicResponse =
        eapi("/album/detail/dynamic", mapOf("id" to id))

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
