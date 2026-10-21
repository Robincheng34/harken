// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.player.unblock

import im.missuo.kumone.net.model.Track
import im.missuo.kumone.player.DURATION_TOLERANCE_MS
import im.missuo.kumone.player.UnblockCandidate
import im.missuo.kumone.player.UnblockSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 灰色解锁真实现：B 站 + 咪咕免费曲 + iOS 三源（pyncmd / 酷我 / 酷狗）。
 *
 * **2026-10-21 真机实测结论**：
 * - B 站（**免登录、免 VIP**）能拿到完整音轨：《晴天》《我不难过》实测 2.8~3.9MB、
 *   时长与曲目一致，故置首选（见 [bilibili]）。
 * - 咪咕（免登录）只能拿**免费曲**完整 MP3；VIP 曲接口返回 JSON，探测后拒绝，
 *   用于 B 站找不到时兜底，**不解锁 VIP**（见 [migu]）。
 * - iOS 原三源已全部失效（上游收紧）：
 * 1. 酷我——搜索/匹配链路可用，但 `anti.s` 与 `mobi.s` 对**所有**曲目只返回
 *    ~11s 的试听样本（`duration:11`），故必须经 [isLikelyFullAudio] 大小校验拒绝。
 * 2. pyncmd——`music-api.gdstudio.xyz`（含 http / 其他子域）全部被 Cloudflare
 *    人机验证拦截，返回 HTML 而非 JSON。
 * 3. 酷狗——`/i/v2/` 对全部曲目返回 `status=2`（无 url，key 已失效），
 *    换 `kgcloud` / `kgcloud2` 等密钥亦无 url。
 *
 * 三源保留作兜底（上游恢复时零改动接入）；其候选仍需经 [isLikelyFullAudio] 大小校验，
 * 避免酷我 11s 残片被当整首播放。
 *
 * `resolve` / `resolveCandidate` 共用同一条四源链路：依次尝试、首个通过匹配守卫
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

    // ── 哔哩哔哩（免登录，2026-10 实测唯一可用的免费整首源）──────────────────
    // 流程：SPI 取匿名 buvid → search/type 搜投稿 → view 取 cid → playurl(fnval=16)
    //       → dash.audio 最高码率音轨。实测《晴天》《我不难过》都能拿到完整 2.8~3.9MB 音轨。
    // 必须带 CookieJar：search 会下发 b_nut 等 cookie，playurl 依赖同一会话。
    private val biliJar = MemoryCookieJar()
    private val biliClient: OkHttpClient = client.newBuilder().cookieJar(biliJar).build()

    @Volatile
    private var biliCookiesReady = false

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
        // ⓪ B 站：免登录、实测唯一能出完整音轨的源，置首。
        val biliResult = runCatching { bilibili(track) }.getOrNull()
        if (biliResult != null && isLikelyFullAudio(biliResult.url, track.durationMS)) {
            return biliResult
        }
        // ① 咪咕免费曲：B 站没有、但咪咕免费时兜底（不解锁 VIP）。
        val miguResult = runCatching { migu(track) }.getOrNull()
        if (miguResult != null && isLikelyFullAudio(miguResult.url, track.durationMS)) {
            return miguResult
        }
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

    // MARK: - ⓪ 哔哩哔哩（免登录，实测唯一可用的免费整首源）

    /**
     * 从 B 站投稿里取原曲音轨（对齐 MusicFree `bilibili` 插件，2026-10 真机实测可用）。
     *
     * 1. `/x/frontend/finger/spi` 取匿名 `buvid3/buvid4`（**无需登录 / 无需 VIP**）；
     * 2. `/x/web-interface/search/type`（`search_type=video`）搜「歌名 + 首位歌手」；
     * 3. 标题/时长匹配后 `/x/web-interface/view` 取 `cid`；
     * 4. `/x/player/playurl?fnval=16` 取 `dash.audio` 中码率最高的一条。
     *
     * 音轨是签名的 HTTPS CDN 地址，实测**不需要 Referer**，ExoPlayer 可直接播放。
     */
    private suspend fun bilibili(track: Track): UnblockCandidate? = withContext(Dispatchers.IO) {
        val results = biliSearch(track) ?: return@withContext null
        val best = results.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { it["type"].stringOrNull() == "video" }
            .mapNotNull { item ->
                val bvid = item["bvid"].stringOrNull()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val title = stripHtml(item["title"].stringOrNull().orEmpty())
                val durationMs = parseBiliDuration(item["duration"].stringOrNull())
                if (!matchesBilibili(track, title, durationMs)) return@mapNotNull null
                BiliVideo(bvid, title, item["author"].stringOrNull().orEmpty(), durationMs)
            }
            // 标题/歌手都对上时，时长最接近的通常是原曲而不是串烧/合集。
            .minByOrNull { abs(it.durationMs - track.durationMS) }
            ?: return@withContext null

        val cid = biliCid(best.bvid) ?: return@withContext null
        val audioUrl = biliAudioUrl(best.bvid, cid) ?: return@withContext null
        UnblockCandidate(
            url = audioUrl,
            title = best.title,
            artist = best.author,
            durationMS = best.durationMs,
            sourceName = "哔哩哔哩",
        )
    }

    private data class BiliVideo(
        val bvid: String,
        val title: String,
        val author: String,
        val durationMs: Long,
    )

    /** 匿名 buvid（进程内缓存；搜索返回非 JSON 时重建一次）。 */
    private fun ensureBiliCookies(): Boolean {
        if (biliCookiesReady) return true
        return runCatching {
            val request = Request.Builder()
                .url("https://api.bilibili.com/x/frontend/finger/spi")
                .header("User-Agent", BILI_UA)
                .build()
            biliClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use false
                val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject
                    ?: return@use false
                val b3 = data["b_3"].stringOrNull() ?: return@use false
                val b4 = data["b_4"].stringOrNull() ?: return@use false
                val spi = "https://api.bilibili.com/x/frontend/finger/spi".toHttpUrl()
                biliJar.saveFromResponse(
                    spi,
                    listOf(
                        Cookie.Builder().domain("bilibili.com").path("/").name("buvid3").value(b3).build(),
                        Cookie.Builder().domain("bilibili.com").path("/").name("buvid4").value(b4).build(),
                    ),
                )
                biliCookiesReady = true
                true
            }
        }.getOrDefault(false)
    }

    private suspend fun biliSearch(track: Track): JsonArray? = withContext(Dispatchers.IO) {
        if (!ensureBiliCookies()) return@withContext null
        val keyword = "${track.name} ${track.artists.firstOrNull()?.name.orEmpty()}".trim()
        val url = "https://api.bilibili.com/x/web-interface/search/type".toHttpUrl().newBuilder()
            .addQueryParameter("context", "")
            .addQueryParameter("page", "1")
            .addQueryParameter("order", "")
            .addQueryParameter("page_size", "20")
            .addQueryParameter("keyword", keyword)
            .addQueryParameter("duration", "")
            .addQueryParameter("tids_1", "")
            .addQueryParameter("tids_2", "")
            .addQueryParameter("__refresh__", "true")
            .addQueryParameter("_extra", "")
            .addQueryParameter("highlight", "1")
            .addQueryParameter("single_column", "0")
            .addQueryParameter("platform", "pc")
            .addQueryParameter("from_source", "")
            .addQueryParameter("search_type", "video")
            .addQueryParameter("dynamic_offset", "0")
            .build()

        repeat(2) { attempt ->
            val body = runCatching {
                biliClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", BILI_UA)
                        .header("Referer", "https://search.bilibili.com")
                        .build(),
                ).execute().use { it.body?.string() }
            }.getOrNull()
            val result = (body
                ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                ?.let { (it as? JsonObject)?.get("data") as? JsonObject }
                ?.get("result")) as? JsonArray
            if (result != null) return@withContext result
            if (attempt == 0) {
                // 412 / 风控时重建 buvid 再试一次。
                biliCookiesReady = false
                ensureBiliCookies()
                delay(700)
            }
        }
        null
    }

    private suspend fun biliCid(bvid: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            biliClient.newCall(
                Request.Builder()
                    .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
                    .header("User-Agent", BILI_UA)
                    .build(),
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use null
                val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject
                data?.get("cid").stringOrNull()?.toLongOrNull()
            }
        }.getOrNull()
    }

    private suspend fun biliAudioUrl(bvid: String, cid: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            biliClient.newCall(
                Request.Builder()
                    .url("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&fnval=16")
                    .header("User-Agent", BILI_UA)
                    .build(),
            ).execute().use { response ->
                val body = response.body?.string() ?: return@use null
                val data = (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonObject
                val audios = (data?.get("dash") as? JsonObject)?.get("audio") as? JsonArray
                    ?: return@use null
                audios.asSequence()
                    .mapNotNull { it as? JsonObject }
                    .maxByOrNull { it["bandwidth"].stringOrNull()?.toLongOrNull() ?: 0L }
                    ?.let { it["baseUrl"].stringOrNull() ?: it["base_url"].stringOrNull() }
            }
        }.getOrNull()
    }

    /** B 站标题匹配：标题需同时包含歌名与首位歌手，时长差 ≤5s，版本标记一致。 */
    private fun matchesBilibili(track: Track, title: String, durationMs: Long): Boolean {
        if (track.durationMS > 0 && durationMs > 0) {
            if (abs(track.durationMS - durationMs) > DURATION_TOLERANCE_MS) return false
        }
        val normalizedTitle = normalize(title)
        val name = normalize(track.name)
        if (name.isEmpty() || !normalizedTitle.contains(name)) return false
        val artist = normalize(track.artists.firstOrNull()?.name.orEmpty())
        if (artist.isNotEmpty() && !normalizedTitle.contains(artist)) return false
        if (versionTags(track.name) != versionTags(title)) return false
        // 原曲不应命中演唱会/现场版；曲目本身是 Live 时放行。
        if (!versionTags(track.name).contains("live") &&
            (normalizedTitle.contains("演唱会") || normalizedTitle.contains("现场"))
        ) {
            return false
        }
        // 教程/谱/翻唱等投稿不是原曲；除非曲目本身就叫这个。
        if (BILI_JUNK.any { normalizedTitle.contains(it) } && BILI_JUNK.none { name.contains(it) }) {
            return false
        }
        return true
    }

    /** `"4:30"` → 270000ms。 */
    private fun parseBiliDuration(value: String?): Long {
        val match = Regex("(\\d+):(\\d+)").find(value ?: return 0L) ?: return 0L
        return (match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 1000
    }

    /** 去掉 `<em class="keyword">` 等高亮标签与 HTML 实体。 */
    private fun stripHtml(value: String): String = value
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    /** 极简内存 CookieJar（B 站 playurl 依赖 search 同会话持有的 cookie）。 */
    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            for (cookie in cookies) {
                val list = store.getOrPut(cookie.domain) { mutableListOf() }
                synchronized(list) {
                    list.removeAll { it.name == cookie.name && it.path == cookie.path }
                    list.add(cookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val out = mutableListOf<Cookie>()
            for (list in store.values) {
                synchronized(list) { out.addAll(list.filter { it.matches(url) }) }
            }
            return out
        }
    }

    // MARK: - 咪咕免费曲（免登录；VIP 曲会返回 JSON，探测后拒绝）

    /**
     * 咪咕音乐（免登录），只能拿到**免费曲**的完整 MP3。
     *
     * 搜索走官方 app 接口 `search_all.do`；播放走 `listenSong.do`——它本身就是一个
     * 302 到 CDN 的**音频流地址**（不是 JSON），ExoPlayer 直接播即可。
     * 对 VIP 曲该接口返回 `{"code":"200000","info":"暂不提供试听地址"}`，
     * 所以先用 `Range: bytes=0-0` 探测 Content-Type，非 audio 一律拒绝。
     *
     * 定位：B 站找不到、但咪咕有免费版时兜底；**不解锁 VIP**。
     */
    private suspend fun migu(track: Track): UnblockCandidate? = withContext(Dispatchers.IO) {
        val keyword = "${track.name} ${track.artists.firstOrNull()?.name.orEmpty()}".trim()
        val searchUrl = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do"
            .toHttpUrl().newBuilder()
            .addQueryParameter("ua", "Android_migu")
            .addQueryParameter("version", "5.0")
            .addQueryParameter("text", keyword)
            .addQueryParameter("pageNo", "1")
            .addQueryParameter("pageSize", "20")
            .addQueryParameter("searchSwitch", """{"song":1}""")
            .build()
        val body = runCatching {
            client.newCall(
                Request.Builder().url(searchUrl).header("User-Agent", MIGU_UA).build(),
            ).execute().use { it.body?.string() }
        }.getOrNull() ?: return@withContext null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return@withContext null
        val results = (root["songResultData"] as? JsonObject)?.get("result") as? JsonArray
            ?: return@withContext null

        val match = results.asSequence()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { item ->
                val songArtists = (item["singers"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.get("name").stringOrNull() }
                    .orEmpty()
                matchesMigu(track, item["name"].stringOrNull().orEmpty(), songArtists)
            } ?: return@withContext null

        val copyrightId = match["copyrightId"].stringOrNull() ?: return@withContext null
        val contentId = match["contentId"].stringOrNull() ?: return@withContext null
        val playUrl = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/sub/listenSong.do"
            .toHttpUrl().newBuilder()
            .addQueryParameter("toneFlag", "PQ")
            .addQueryParameter("netType", "00")
            .addQueryParameter("userId", MIGU_USER_ID)
            .addQueryParameter("ua", "Android_migu")
            .addQueryParameter("version", "5.0")
            .addQueryParameter("channel", "0146921")
            .addQueryParameter("copyrightId", copyrightId)
            .addQueryParameter("contentId", contentId)
            .addQueryParameter("resourceType", "2")
            .build()
            .toString()
        if (!miguPlayable(playUrl)) return@withContext null

        val songArtists = (match["singers"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("name").stringOrNull() }
            .orEmpty()
        UnblockCandidate(
            url = playUrl,
            title = match["name"].stringOrNull().orEmpty(),
            artist = songArtists.joinToString(" / "),
            // 搜索接口不返回时长；用曲目时长，供外层 isPlausibleDuration 校验通过。
            durationMS = track.durationMS,
            sourceName = "咪咕音乐",
        )
    }

    /** 探测 listenSong.do 最终返回的是音频流（免费曲）还是 JSON（VIP 无地址）。 */
    private suspend fun miguPlayable(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", MIGU_UA)
                    .header("Range", "bytes=0-0")
                    .build(),
            ).execute().use { response ->
                val type = response.body?.contentType()?.toString().orEmpty()
                response.isSuccessful && (type.startsWith("audio") || type.contains("octet-stream"))
            }
        }.getOrDefault(false)
    }

    /** 咪咕匹配：歌名归一化相等、歌手命中、版本标记一致（搜索无时长）。 */
    private fun matchesMigu(track: Track, name: String, artists: List<String>): Boolean {
        if (normalize(name) != normalize(track.name)) return false
        if (versionTags(track.name) != versionTags(name)) return false
        val expected = normalize(track.artists.firstOrNull()?.name.orEmpty())
        if (expected.isEmpty()) return false
        val candidates = artists.map { normalize(it) }
        if (candidates.none { it == expected || it.contains(expected) || expected.contains(it) }) {
            return false
        }
        if (BILI_JUNK.any { normalize(name).contains(it) } &&
            BILI_JUNK.none { normalize(track.name).contains(it) }
        ) {
            return false
        }
        return true
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

        /** B 站接口要求浏览器 UA（OkHttp 默认 UA 易被风控）。 */
        private const val BILI_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** B 站投稿里不属于原曲的关键词（教程/谱/伴奏等）。 */
        private val BILI_JUNK = listOf(
            "钢琴", "鼓谱", "琴谱", "音高", "教程", "纯音乐", "伴奏", "翻唱", "cover",
        )

        /** 咪咕官方 app 接口要求的 UA 与固定访客 id。 */
        private const val MIGU_UA = "Mozilla/5.0"
        private const val MIGU_USER_ID = "1554861458870011"

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
