// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.player.unblock

import im.missuo.kumone.net.model.Track
import im.missuo.kumone.player.DURATION_TOLERANCE_MS
import im.missuo.kumone.player.UnblockCandidate
import im.missuo.kumone.player.UnblockSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 灰色解锁真实现（iOS `AudioSourceProvider` 的三源：pyncmd / 酷我 / 酷狗）。
 *
 * 与 iOS 一致：三源全接、默认开（见 [im.missuo.kumone.storage.settings.AppSettings.unblockEnabled]）。
 *
 * **2026-10-21 真机实测：三者当前均已不可用（上游收紧）**：
 * 1. 酷我——搜索/匹配链路可用，但 `anti.s` 与 `mobi.s` 对**所有**曲目只返回
 *    ~11s 的试听样本（`duration:11`），故必须经 [isLikelyFullAudio] 大小校验拒绝。
 * 2. pyncmd——`music-api.gdstudio.xyz`（含 http / 其他子域）全部被 Cloudflare
 *    人机验证拦截，返回 HTML 而非 JSON。
 * 3. 酷狗——`/i/v2/` 对全部曲目返回 `status=2`（无 url，key 已失效），
 *    换 `kgcloud` / `kgcloud2` 等密钥亦无 url。
 *
 * 因此当前**无法真正解锁 VIP 曲目**：管线会在大小校验失败后返回 null，上层回落到
 * 官方试听片段（至少时长与提示是诚实的）。保留三源与匹配逻辑，便于上游恢复或
 * 改用可用音源时零改动接入。
 *
 * `resolve` / `resolveCandidate` 共用同一条三源链路：依次尝试、首个通过匹配守卫
 * 的直链即被采纳；任一源失败（网络异常 / JSON 解析失败 / 歌名不匹配）一律跳下一源，
 * 绝不向外抛异常（外层 `runCatching` 兜底）。直链 `http://` 一律升 `https://`
 * （外层 [im.missuo.kumone.player.SongUrlResolver] 也会再升一次，这里保持幂等）。
 *
 * 匹配守卫对齐 iOS `AudioSourceTrackMatcher.matches`：标题归一化相等、歌手命中
 * （候选歌手按分隔符拆分后包含曲目首位歌手）、时长差 ≤ 5s、版本标记
 * （live/remix/dj/伴奏/cover/翻唱/instrumental/karaoke）一致，才视为同一首，
 * 避免错配翻唱/伴奏/现场版。pyncmd 按网易云 id 直取，无需匹配。
 */
class NeteaseUnblockSource(
    private val client: OkHttpClient = defaultClient(),
) : UnblockSource {

    /** 容忍未知字段 / 宽松格式（第三方源 JSON 字段不齐，按 iOS 的宽容 Codable 处理）。 */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 带元数据的候选：经匹配守卫后返回，供 [im.missuo.kumone.player.SongUrlResolver.rescue]
     * 做时长校验并带出 [UnblockCandidate.sourceName]（驱动「已使用第三方音源：X」提示）。
     * 未命中任何源返回 null。
     */
    override suspend fun resolveCandidate(track: Track): UnblockCandidate? = resolveInternal(track)

    /** 宽松路径：只取直链（无元数据）。与 [resolveCandidate] 走同一匹配逻辑，避免错配。 */
    override suspend fun resolve(track: Track): String? = resolveInternal(track)?.url

    /**
     * 三源依次尝试，返回第一个通过匹配守卫且**音频长度合理**的候选；全部失败返回 null。
     *
     * 顺序按实测可用性排列（见类注释）：酷我 → pyncmd → 酷狗。
     * 每个候选在采纳前还会校验直链大小：部分源（如酷我 `anti.s`）对受限曲目只返回
     * 十几秒的试听样本，若直接采纳会让用户听 11 秒就断——宁可判为「解锁失败」，
     * 由上层回落到官方的试听片段（至少时长与提示是诚实的）。
     */
    private suspend fun resolveInternal(track: Track): UnblockCandidate? {
        val kuwoResult = runCatching { kuwo(track) }.getOrNull()
        if (kuwoResult != null && isLikelyFullAudio(kuwoResult.url, track.durationMS)) {
            return kuwoResult
        }
        val pyncmdResult = runCatching { pyncmd(track) }.getOrNull()
        if (pyncmdResult != null && isLikelyFullAudio(pyncmdResult.url, track.durationMS)) {
            return pyncmdResult
        }
        val kugouResult = runCatching { kugou(track) }.getOrNull()
        if (kugouResult != null && isLikelyFullAudio(kugouResult.url, track.durationMS)) {
            return kugouResult
        }
        return null
    }

    /**
     * 直链大小校验：对已知识时长的曲目，HEAD 取 Content-Length 粗估音频是否完整。
     *
     * 部分第三方源对受限曲目只返回 ~11s 的试听样本（如酷我 `anti.s` 当前行为）。
     * 少于 [MIN_FULL_AUDIO_BYTES] 且曲目本身较长时判为不可信（拒绝采纳）。
     * HEAD 失败 / 无 Content-Length 时放行（不因探测失败误伤可用源）。
     */
    private suspend fun isLikelyFullAudio(url: String, trackDurationMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            if (trackDurationMs < SHORT_TRACK_MS) return@withContext true
            runCatching {
                val request = Request.Builder().url(url).head().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use true
                    val length = resp.header("Content-Length")?.toLongOrNull() ?: return@use true
                    length >= MIN_FULL_AUDIO_BYTES
                }
            }.getOrDefault(true)
        }

    // MARK: - ① 酷我（首选）

    private suspend fun kuwo(track: Track): UnblockCandidate? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(keyword(track), "UTF-8")
        val searchUrl = "https://search.kuwo.cn/r.s?&correct=1&vipver=1&stype=comprehensive" +
            "&encoding=utf8&rformat=json&mobi=1&show_copyright_off=1&searchapi=6&all=$query"
        val searchBody = getText(searchUrl, userAgent = KUWO_UA) ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(searchBody) }.getOrNull() as? JsonObject
            ?: return@withContext null
        // iOS `content[1]["musicpage"]["abslist"]`（不是根级 abslist）。
        // content[1] 是 {"musicpage": {...}} 包装层，需拆两层。
        val content = root["content"] as? JsonArray ?: return@withContext null
        val secondEl = content.getOrNull(1) as? JsonObject ?: return@withContext null
        val musicPage = secondEl["musicpage"] as? JsonObject ?: return@withContext null
        val abslist = musicPage["abslist"] as? JsonArray ?: return@withContext null

        // 取前 5 条里首个通过匹配守卫的（iOS `songs.prefix(5)`）。
        val match = abslist.asSequence()
            .mapNotNull { it as? JsonObject }
            .take(CANDIDATE_LIMIT)
            .firstOrNull { candidate ->
                matches(
                    track = track,
                    candTitle = candidate["SONGNAME"].stringOrNull().orEmpty(),
                    candArtist = candidate["ARTIST"].stringOrNull().orEmpty(),
                    candDurationMs = candidate["DURATION"].parseDurationMs() ?: 0L,
                )
            }
        if (match == null) return@withContext null

        val musicRid = match["MUSICRID"].stringOrNull() ?: return@withContext null
        val rid = musicRid.substringAfterLast('_').ifBlank { musicRid }
        val convertUrl = "https://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3" +
            "&response=url&rid=MUSIC_$rid"
        val convertBody = getText(convertUrl, userAgent = KUWO_UA) ?: return@withContext null
        // 响应是纯文本（可能含换行/附加字段），用正则提取首个 http(s) 直链（iOS 同款）。
        val direct = HTTP_URL.find(convertBody)?.value ?: return@withContext null
        if (!direct.startsWith("http")) return@withContext null

        UnblockCandidate(
            url = direct,
            title = match["SONGNAME"].stringOrNull().orEmpty(),
            artist = match["ARTIST"].stringOrNull().orEmpty(),
            durationMS = match["DURATION"].parseDurationMs() ?: 0L,
            sourceName = "酷我音乐",
        )
    }

    // MARK: - ② pyncmd 公共镜像

    private suspend fun pyncmd(track: Track): UnblockCandidate? = withContext(Dispatchers.IO) {
        val url = "https://music-api.gdstudio.xyz/api.php?" +
            "types=url&source=netease&id=${track.id}&br=320"
        val body = getText(url) ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return@withContext null
        // iOS 先校验 br > 0 再取 url；镜像被 CF 拦截时是整个 HTML，解析会在上一步失败。
        val br = root["br"].stringOrNull()?.toIntOrNull() ?: 0
        if (br <= 0) return@withContext null
        val direct = root["url"].stringOrNull() ?: return@withContext null
        if (!direct.startsWith("http")) return@withContext null
        // 按 id 直取，必然是同一首 → 用曲目自身元数据（时长差恒为 0，通过时长校验）。
        UnblockCandidate(
            url = direct,
            title = track.name,
            artist = track.artistNames,
            durationMS = track.durationMS,
            sourceName = "pyncmd 公共镜像",
        )
    }

    // MARK: - ③ 酷狗

    private suspend fun kugou(track: Track): UnblockCandidate? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(keyword(track), "UTF-8")
        val searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?format=json" +
            "&keyword=$query&page=1&pagesize=10"
        val searchBody = getText(searchUrl) ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(searchBody) }.getOrNull() as? JsonObject
            ?: return@withContext null
        val data = root["data"] as? JsonObject ?: return@withContext null
        // iOS `data["info"]`（不是 `list`）。
        val info = data["info"] as? JsonArray ?: return@withContext null

        val match = info.asSequence()
            .mapNotNull { it as? JsonObject }
            .take(CANDIDATE_LIMIT)
            .firstOrNull { candidate ->
                matches(
                    track = track,
                    candTitle = candidate["songname"].stringOrNull().orEmpty(),
                    candArtist = candidate["singername"].stringOrNull().orEmpty(),
                    candDurationMs = candidate["duration"].parseDurationMs() ?: 0L,
                )
            } ?: return@withContext null

        val hash = match["hash"].stringOrNull() ?: return@withContext null
        // album_id 可能是字符串或数字，统一取其字面量。
        val albumId = (match["album_id"] as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }?.content.orEmpty()
        val key = md5Hex(hash + "kgcloudv2")
        val playUrl = "https://trackercdn.kugou.com/i/v2/?key=$key&hash=$hash" +
            "&appid=1005&pid=2&cmd=25&behavior=play&album_id=$albumId"
        val playBody = getText(playUrl) ?: return@withContext null
        val playRoot = runCatching { json.parseToJsonElement(playBody) }.getOrNull() as? JsonObject
            ?: return@withContext null
        val direct = extractKugouUrl(playRoot["url"]) ?: return@withContext null
        if (!direct.startsWith("http")) return@withContext null

        UnblockCandidate(
            url = direct,
            title = match["songname"].stringOrNull().orEmpty(),
            artist = match["singername"].stringOrNull().orEmpty(),
            durationMS = match["duration"].parseDurationMs() ?: 0L,
            sourceName = "酷狗音乐",
        )
    }

    // MARK: - 匹配守卫（iOS AudioSourceTrackMatcher.matches）

    /**
     * 标题归一化相等、歌手命中、时长差 ≤ 5s、版本标记一致，才视为同一首。
     * 任一不满足即拒绝该候选（跳到下一源），避免错配翻唱/伴奏/现场版。
     *
     * 与 iOS 一致：曲目与候选的时长都必须为正（未知时长直接拒绝，宁可不解锁也不错配）。
     */
    private fun matches(
        track: Track,
        candTitle: String,
        candArtist: String,
        candDurationMs: Long,
    ): Boolean {
        if (track.durationMS <= 0 || candDurationMs <= 0) return false
        if (abs(track.durationMS - candDurationMs) > DURATION_TOLERANCE_MS) return false
        if (normalize(track.name) != normalize(candTitle)) return false
        if (versionTags(track.name) != versionTags(candTitle)) return false
        // iOS 用首位歌手作为期望值：候选歌手按分隔符拆分后必须包含它。
        val expected = normalize(track.artists.firstOrNull()?.name.orEmpty())
        if (expected.isEmpty()) return false
        return splitArtists(candArtist).any { it == expected }
    }

    /** 歌手串按 iOS 的分隔符集合拆分并归一化（支持「A/B」「A&B」「A、B」等）。 */
    private fun splitArtists(value: String): List<String> =
        value.split('/', '&', '、', ',', '，', ';', '；')
            .map { normalize(it) }
            .filter { it.isNotEmpty() }

    /** 版本标记集合（iOS `versionMarkers`）。 */
    private fun versionTags(s: String): Set<String> {
        val normalized = normalize(s)
        return VERSION_TAGS.filter { normalized.contains(it) }.toSet()
    }

    /** 归一化：小写 + 仅保留字母/数字（等价 iOS 的 alphanumerics 过滤）。 */
    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    // MARK: - 网络 / 工具

    /** iOS 的搜索关键词 = 歌名 + 首位歌手。 */
    private fun keyword(track: Track): String =
        "${track.name} ${track.artists.firstOrNull()?.name.orEmpty()}".trim()

    /** 执行 GET，失败返回 null（不抛）。 */
    private suspend fun getText(url: String, userAgent: String? = null): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).get().apply {
                    if (userAgent != null) header("User-Agent", userAgent)
                }.build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                }
            }.getOrNull()
        }

    /** 酷狗云 key：MD5(hash + "kgcloudv2") 小写 hex。 */
    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 酷狗 `/i/v2/` 的 `url` 可能是字符串数组（多备线）或单字符串，均取首条。 */
    private fun extractKugouUrl(element: JsonElement?): String? = when (element) {
        is JsonArray -> (element.firstOrNull() as? JsonPrimitive)?.content
        is JsonPrimitive -> element.content
        else -> null
    }

    companion object {
        /** 酷我搜索必须带此 UA，否则返回空结果。 */
        private const val KUWO_UA = "okhttp/3.10.0"

        /** 每个源只匹配前 N 条候选（iOS `prefix(5)`）。 */
        private const val CANDIDATE_LIMIT = 5

        /** 短于此时长的曲目不做大小校验（铃声/小品等本身文件就小）。 */
        private const val SHORT_TRACK_MS = 90_000L

        /** 完整歌曲的保守最小字节数（128kbps 下约 25s）；低于此值视为试听样本。 */
        private const val MIN_FULL_AUDIO_BYTES = 400_000L

        /** 从酷我转直链接口返回的纯文本中提取首个 http(s) 直链。 */
        private val HTTP_URL = Regex("""https?://[^\s"']+""")

        /** 版本标记词（小写 / 中文，iOS `versionMarkers`）。 */
        private val VERSION_TAGS = listOf(
            "live", "remix", "伴奏", "dj", "cover", "翻唱", "instrumental", "karaoke",
        )

        /** 自建 client：连接 15s、读取 30s（与 iOS 解锁超时对齐）。 */
        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** 取 JSON 字符串值；元素为 null / JsonNull 时返回 null（数字按字面量返回）。 */
private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/**
 * 解析时长（秒或毫秒 → 毫秒）。
 * 酷我 `DURATION` / 酷狗 `duration` 多为秒；超过 10 万视为已是毫秒，避免误乘。
 */
private fun JsonElement?.parseDurationMs(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    val raw = primitive.content.toLongOrNull() ?: return null
    return if (raw > 100_000L) raw else raw * 1000L
}
