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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 灰色解锁真实现（iOS `AudioSourceProvider` 的三源：pyncmd / 酷我 / 酷狗）。
 *
 * 与 iOS 一致：三源全接、默认开（见 [im.missuo.kumone.storage.settings.AppSettings.unblockEnabled]）。
 *
 * `resolve` / `resolveCandidate` 共用同一条三源链路：依次尝试、首个通过匹配守卫
 * 的直链即被采纳；任一源失败（网络异常 / JSON 解析失败 / 歌名不匹配）一律跳下一源，
 * 绝不向外抛异常（外层 `runCatching` 兜底）。直链 `http://` 一律升 `https://`
 * （外层 [im.missuo.kumone.player.SongUrlResolver] 也会再升一次，这里保持幂等）。
 *
 * 匹配守卫（iOS `AudioSourceTrackMatcher.matches`）：标题归一化相等、歌手命中
 * （包含关系）、时长差 ≤ 5s、版本标记（live/remix/dj/伴奏/翻唱）一致，才视为同一首，
 * 避免错配翻唱/伴奏/现场版。pyncmd 按网易云 id 直取，无需匹配。
 */
class NeteaseUnblockSource(
    private val client: OkHttpClient = defaultClient(),
) : UnblockSource {

    /** 容忍未知字段 / 宽松格式（第三方源 JSON 字段不齐，按 iOS 的宽容 Codable 处理）。 */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 带元数据的候选：经匹配守卫后返回，供 [im.missuo.kumone.player.SongUrlResolver.rescue]
     * 做 `isPlausibleMatch` 时长校验并带出 [UnblockCandidate.sourceName]（驱动
     * 「已使用第三方音源：X」提示）。未命中任何源返回 null。
     */
    override suspend fun resolveCandidate(track: Track): UnblockCandidate? = resolveInternal(track)

    /** 宽松路径：只取直链（无元数据）。与 [resolveCandidate] 走同一匹配逻辑，避免错配。 */
    override suspend fun resolve(track: Track): String? = resolveInternal(track)?.url

    /** 三源依次尝试，返回第一个通过匹配守卫的候选；全部失败返回 null。 */
    private suspend fun resolveInternal(track: Track): UnblockCandidate? {
        // ① pyncmd 公共镜像（首选，按网易云 id 直取，无需匹配）。
        runCatching { pyncmd(track)?.let { return it } }
        // ② 酷我：搜索 + 歌名/歌手/时长匹配守卫。
        runCatching { kuwo(track)?.let { return it } }
        // ③ 酷狗：搜索 + 歌名/歌手/时长匹配守卫。
        runCatching { kugou(track)?.let { return it } }
        return null
    }

    // MARK: - ① pyncmd 公共镜像

    private suspend fun pyncmd(track: Track): UnblockCandidate? = withContext(Dispatchers.IO) {
        val url = "https://music-api.gdstudio.xyz/api.php?" +
            "types=url&source=netease&id=${track.id}&br=320"
        val body = getText(url) ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return@withContext null
        val direct = root["url"].stringOrNull() ?: return@withContext null
        if (!direct.startsWith("http")) return@withContext null
        // 按 id 直取，必然是同一首 → 用曲目自身元数据，时长差恒为 0（通过时长校验）。
        UnblockCandidate(
            url = direct,
            title = track.name,
            artist = track.artistNames,
            durationMS = track.durationMS,
            sourceName = "pyncmd 公共镜像",
        )
    }

    // MARK: - ② 酷我

    private suspend fun kuwo(track: Track): UnblockCandidate? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(track.name, "UTF-8")
        val searchUrl = "https://search.kuwo.cn/r.s?correct=1&vipver=1&stype=comprehensive" +
            "&encoding=utf8&rformat=json&mobi=1&show_copyright_off=1&searchapi=6&all=$query"
        val searchBody = getText(searchUrl, userAgent = KUWO_UA) ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(searchBody) }.getOrNull() as? JsonObject
            ?: return@withContext null
        val abslist = root["abslist"] as? JsonArray ?: return@withContext null
        if (abslist.isEmpty()) return@withContext null
        val first = abslist.firstOrNull() as? JsonObject ?: return@withContext null
        val musicRid = first["MUSICRID"].stringOrNull() ?: return@withContext null
        if (!musicRid.startsWith("MUSIC_")) return@withContext null
        val candTitle = first["SONGNAME"].stringOrNull().orEmpty()
        val candArtist = first["ARTIST"].stringOrNull().orEmpty()
        val candDurationMs = first["DURATION"].parseDurationMs() ?: 0L
        if (!matches(track, candTitle, candArtist, candDurationMs)) return@withContext null
        val convertUrl = "https://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3" +
            "&response=url&rid=$musicRid"
        val direct = getText(convertUrl, userAgent = KUWO_UA)?.trim() ?: return@withContext null
        if (!direct.startsWith("http")) return@withContext null
        UnblockCandidate(
            url = direct,
            title = candTitle,
            artist = candArtist,
            durationMS = candDurationMs,
            sourceName = "酷我音乐",
        )
    }

    // MARK: - ③ 酷狗

    private suspend fun kugou(track: Track): UnblockCandidate? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(track.name, "UTF-8")
        val searchUrl = "http://mobilecdn.kugou.com/api/v3/search/song?format=json" +
            "&keyword=$query&page=1&pagesize=10"
        val searchBody = getText(searchUrl) ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(searchBody) }.getOrNull() as? JsonObject
            ?: return@withContext null
        val data = root["data"] as? JsonObject ?: return@withContext null
        val list = data["list"] as? JsonArray ?: return@withContext null
        if (list.isEmpty()) return@withContext null
        val first = list.firstOrNull() as? JsonObject ?: return@withContext null
        val hash = first["hash"].stringOrNull() ?: return@withContext null
        val albumId = first["album_id"].stringOrNull().orEmpty()
        val candTitle = first["songname"].stringOrNull().orEmpty()
        val candArtist = first["singername"].stringOrNull().orEmpty()
        val candDurationMs = first["duration"].parseDurationMs() ?: 0L
        if (!matches(track, candTitle, candArtist, candDurationMs)) return@withContext null
        val key = md5Hex(hash + "kgcloudv2")
        val playUrl = "https://trackercdn.kugou.com/i/v2/?key=$key&hash=$hash" +
            "&appid=1005&pid=2&cmd=25&behavior=play&album_id=$albumId"
        val playBody = getText(playUrl) ?: return@withContext null
        val playRoot = runCatching { json.parseToJsonElement(playBody) }.getOrNull() as? JsonObject
            ?: return@withContext null
        val playData = playRoot["data"] as? JsonObject ?: return@withContext null
        val direct = extractKugouUrl(playData["url"]) ?: return@withContext null
        if (!direct.startsWith("http")) return@withContext null
        UnblockCandidate(
            url = direct,
            title = candTitle,
            artist = candArtist,
            durationMS = candDurationMs,
            sourceName = "酷狗音乐",
        )
    }

    // MARK: - 匹配守卫（iOS AudioSourceTrackMatcher.matches）

    /**
     * 标题归一化相等、歌手命中（包含关系）、时长差 ≤ 5s、版本标记一致，才视为同一首。
     * 任一不满足即拒绝该候选（跳到下一源），避免错配翻唱/伴奏/现场版。
     */
    private fun matches(
        track: Track,
        candTitle: String,
        candArtist: String,
        candDurationMs: Long,
    ): Boolean {
        if (normalize(track.name) != normalize(candTitle)) return false
        if (!artistMatches(track.artistNames, candArtist)) return false
        if (track.durationMS > 0 && candDurationMs > 0) {
            if (kotlin.math.abs(track.durationMS - candDurationMs) > DURATION_TOLERANCE_MS) return false
        }
        if (!versionTagsConsistent(track.name, candTitle)) return false
        return true
    }

    /** 歌手归一化后双向包含即视为命中（合作曲 / 多歌手名均可覆盖）。 */
    private fun artistMatches(trackArtists: String, candArtist: String): Boolean {
        val normTrack = normalize(trackArtists)
        val normCand = normalize(candArtist)
        if (normTrack.isEmpty() || normCand.isEmpty()) return true
        return normTrack.contains(normCand) || normCand.contains(normTrack)
    }

    /**
     * 版本标记一致：一侧有标记而另一侧没有（或两者标记不同）→ 不一致。
     * 例：原曲 vs (live / remix / dj / 伴奏 / 翻唱) 版必须区分开。
     */
    private fun versionTagsConsistent(a: String, b: String): Boolean =
        extractVersionTags(a) == extractVersionTags(b)

    private fun extractVersionTags(s: String): Set<String> {
        val lower = s.lowercase(Locale.ROOT)
        return VERSION_TAGS.filter { lower.contains(it) }.toSet()
    }

    /** 去除空白与标点后小写，用于标题/歌手归一化比较。 */
    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[\\s\\p{Punct}]"), "")

    // MARK: - 网络 / 工具

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

        /** 版本标记词（小写 / 中文）。 */
        private val VERSION_TAGS = listOf("live", "remix", "dj", "伴奏", "翻唱", "instrumental")

        /** 自建 client：连接 15s、读取 30s（与 iOS 解锁超时对齐）。 */
        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** 取 JSON 字符串值；元素为 null / 非字符串基元 / JsonNull 时返回 null。 */
private fun JsonElement?.stringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content
}

/**
 * 解析时长（秒或毫秒 → 毫秒）。
 * 酷我 `DURATION` / 酷狗 `duration` 多为秒；超过 10 万视为已是毫秒，避免误乘。
 */
private fun JsonElement?.parseDurationMs(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    val raw = primitive.content.toLongOrNull() ?: return null
    return if (raw > 100_000L) raw else raw * 1000L
}
