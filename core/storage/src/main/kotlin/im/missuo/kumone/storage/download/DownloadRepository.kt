// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.storage.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import im.missuo.kumone.net.api.NeteaseApi
import im.missuo.kumone.net.model.Track
import im.missuo.kumone.storage.db.DownloadedSongEntity
import im.missuo.kumone.storage.db.KumoneDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 已下载歌曲仓储：把 Netease [NeteaseApi.songURL] 返回的直链字节流落盘到应用私有外部
 * 目录（`getExternalFilesDir(DIRECTORY_MUSIC)/kumone/`），并用 Room 记录下载清单；
 * 同时实现 [LocalSongSource] 供播放器解析时优先走本地 `file://` URI，零改 ExoPlayer
 * 即可离线播放。
 *
 * 网易云没有独立下载端点：`songURL` 返回的 `url` 就是落在 `*.music.126.net` 的下载直链，
 * 下载 = 取该 url → OkHttp 把字节流写入本地。试听片段（`freeTrialInfo != null`）会被
 * 拒绝，避免把 30 秒试听当整曲存储。文件落盘在应用私有目录，**无需任何存储权限**，
 * 卸载自动清理。
 *
 * 生命周期：**下载工作跑在 [scope]（进程级 IO），与 UI 解耦**——离开播放页也不会中断
 * 下载，也不会因协程取消删除已落盘文件。UI 通过 [bus] 收事件做 toast。
 */
class DownloadRepository(
    private val context: Context,
    private val api: NeteaseApi,
    private val db: KumoneDatabase,
) : LocalSongSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 下载目录：应用私有外部目录优先，退化到内部 filesDir。 */
    private val dir: File =
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?.resolve("kumone")
            ?.also { it.mkdirs() }
            ?: context.filesDir.resolve("kumone").also { it.mkdirs() }

    /** 内存索引：播放时 [localUri] 是 O(1) 不查 DB（key = "${trackId}_$level"）。 */
    private val index = ConcurrentHashMap<String, DownloadedSongEntity>()

    init {
        // 预热内存索引：取首帧即可，后续 upsert/delete 会同步进 index。
        scope.launch {
            runCatching { db.downloadedSongDao().observeAll().first() }
                .getOrDefault(emptyList())
                .forEach { index[it.id] = it }
        }
    }

    override fun localUri(trackId: Long, level: String): String? =
        index["${trackId}_$level"]
            ?.let { Uri.fromFile(File(dir, it.fileName)).toString() }

    override fun localUriAny(trackId: Long): String? =
        index.values.firstOrNull { it.trackId == trackId }
            ?.let { Uri.fromFile(File(dir, it.fileName)).toString() }

    /** 已下载清单（按下载时间倒序）。 */
    fun observeDownloads(): Flow<List<DownloadedSongEntity>> =
        db.downloadedSongDao().observeAll()

    /**
     * UI 订阅的下载事件总线（进度 / 终态）。[download] 是 fire-and-forget，UI 经此通道
     * 收事件做 toast，本身不参与下载生命周期。
     */
    private val _bus = MutableSharedFlow<DownloadStatus>(extraBufferCapacity = 256)
    val bus: SharedFlow<DownloadStatus> = _bus

    private val userAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    /**
     * 发起下载（fire-and-forget）。实际落盘工作在 [scope]（进程级 IO）上执行，与 UI
     * 生命周期解耦：离开播放页不会中断下载，也不会因协程取消删掉已落盘文件。进度与
     * 终态通过 [bus] 下发，供 UI 做 toast。
     */
    fun download(track: Track, level: String) {
        val key = "${track.id}_$level"
        scope.launch {
            if (index.containsKey(key)) {
                _bus.emit(DownloadStatus.AlreadyExists(track.id, level, track.name))
                return@launch
            }
            _bus.emit(DownloadStatus.Started(track.id, level, track.name))
            var partFile: File? = null
            try {
                // 1) 取真实下载直链。
                val data = try {
                    api.songURL(listOf(track.id), level).firstOrNull()
                } catch (e: Exception) {
                    Log.e(TAG, "songURL 失败: ${track.name}", e)
                    _bus.emit(
                        DownloadStatus.Failed(
                            track.id, level, track.name,
                            "无法获取下载地址：${e.message}",
                        ),
                    )
                    return@launch
                }
                if (data == null) {
                    _bus.emit(
                        DownloadStatus.Failed(track.id, level, track.name, "无法获取下载地址"),
                    )
                    return@launch
                }
                // 跨模块公有属性无法智能转换为非空，用 elvis + return 取得非空 url。
                val downloadUrl = data.url ?: run {
                    _bus.emit(
                        DownloadStatus.Failed(track.id, level, track.name, "无法获取下载地址"),
                    )
                    return@launch
                }
                // 2) 拒绝试听片段：否则会存 30 秒试听当整曲。
                if (data.freeTrialInfo != null) {
                    _bus.emit(
                        DownloadStatus.Failed(
                            track.id, level, track.name,
                            "该歌曲为试听片段，无法下载",
                        ),
                    )
                    return@launch
                }

                // 3) 带 UA + Referer 直连 CDN（缺任一会 403）。
                val ext = data.type?.takeIf { it.isNotBlank() } ?: "mp3"
                val finalFile = File(dir, "${track.id}_$level.$ext")
                partFile = File(dir, "$key.part")

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", userAgent)
                    .header("Referer", "https://music.163.com")
                    .build()

                Log.d(TAG, "开始下载：${track.name} ($level) -> $downloadUrl")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful || response.body == null) {
                        Log.e(TAG, "下载失败 HTTP ${response.code}: ${track.name}")
                        _bus.emit(
                            DownloadStatus.Failed(
                                track.id, level, track.name,
                                "下载失败：HTTP ${response.code}",
                            ),
                        )
                        return@launch
                    }
                    val total = response.body!!.contentLength()
                    var lastEmit = 0L
                    response.body!!.byteStream().use { input ->
                        FileOutputStream(partFile).use { out ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            var bytes = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                                bytes += read
                                if (total > 0 && bytes - lastEmit >= PROGRESS_STEP) {
                                    lastEmit = bytes
                                    _bus.emit(
                                        DownloadStatus.Progress(
                                            track.id, level, track.name, bytes, total,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                // 4) 落盘完成：改名提交，避免 .part 被误判为已下载。
                if (!partFile.renameTo(finalFile)) {
                    partFile.delete()
                    Log.e(TAG, "文件整理失败：${track.name}")
                    _bus.emit(
                        DownloadStatus.Failed(
                            track.id, level, track.name,
                            "下载完成但文件整理失败",
                        ),
                    )
                    return@launch
                }

                val entity = DownloadedSongEntity(
                    id = key,
                    trackId = track.id,
                    level = level,
                    fileName = finalFile.name,
                    title = track.name,
                    artist = track.artistNames,
                    album = track.album.name,
                    albumPicUrl = track.album.picUrl,
                    durationMS = track.durationMS,
                    sizeBytes = finalFile.length(),
                    downloadedAt = System.currentTimeMillis(),
                )
                db.downloadedSongDao().upsert(entity)
                index[key] = entity
                Log.i(
                    TAG,
                    "下载完成：${track.name} ($level) -> ${finalFile.absolutePath}, " +
                        "${finalFile.length()}B",
                )
                _bus.emit(DownloadStatus.Completed(track.id, level, track.name))
            } catch (e: Exception) {
                // 含 CancellationException（进程级 scope 几乎不会被取消，仅防御）。
                partFile?.delete()
                Log.e(TAG, "下载异常：${track.name}", e)
                if (e is kotlinx.coroutines.CancellationException) throw e
                _bus.emit(
                    DownloadStatus.Failed(
                        track.id, level, track.name,
                        e.message ?: "下载失败",
                    ),
                )
            }
        }
    }

    /** 删除某音质下的下载文件 + Room 记录 + 内存索引。 */
    suspend fun delete(trackId: Long, level: String) {
        val key = "${trackId}_$level"
        index[key]?.let { File(dir, it.fileName).delete() }
        db.downloadedSongDao().delete(trackId, level)
        index.remove(key)
    }

    companion object {
        private const val TAG = "KumoneDownload"
        private const val PROGRESS_STEP = 256 * 1024L // 每 256KB 发一次进度

        private val KB: Double = 1024.0
        private val MB: Double = KB * 1024.0

        /** 把字节数格式化为 KB / MB（保留 1 位小数）。 */
        fun formatSize(bytes: Long): String {
            val mb = bytes / MB
            return if (mb >= 1.0) {
                String.format(Locale.ROOT, "%.1f MB", mb)
            } else {
                String.format(Locale.ROOT, "%.1f KB", bytes / KB)
            }
        }
    }
}

/**
 * 下载状态机（密封接口）：调用方按终态与进度更新 UI。每个状态都带 `trackId` / `level` /
 * `title`，便于 UI 直接消费而无需回查当前曲目。
 */
sealed interface DownloadStatus {
    val trackId: Long
    val level: String
    val title: String
    data class Started(
        override val trackId: Long,
        override val level: String,
        override val title: String,
    ) : DownloadStatus
    data class Progress(
        override val trackId: Long,
        override val level: String,
        override val title: String,
        val bytes: Long,
        val total: Long,
    ) : DownloadStatus
    data class Completed(
        override val trackId: Long,
        override val level: String,
        override val title: String,
    ) : DownloadStatus
    data class Failed(
        override val trackId: Long,
        override val level: String,
        override val title: String,
        val reason: String,
    ) : DownloadStatus
    data class AlreadyExists(
        override val trackId: Long,
        override val level: String,
        override val title: String,
    ) : DownloadStatus
}
