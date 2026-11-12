// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 在线检测更新仓储：已装版本读取、清单拉取、APK 下载落盘、安装跳转，
// 以及「待更新」状态的 DataStore 持久化（跨进程重启保留）。

package com.rc.harken.storage.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import com.rc.harken.net.update.UpdateChecker
import com.rc.harken.net.update.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateRepository(
    private val context: Context,
    private val checker: UpdateChecker,
    private val dataStore: DataStore<Preferences>,
) {
    data class InstalledVersion(val versionCode: Int, val versionName: String)
    data class DownloadProgress(val bytes: Long, val total: Long, val fraction: Float)

    suspend fun getInstalledVersion(): InstalledVersion {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") info.versionCode
        }
        return InstalledVersion(code, info.versionName ?: "")
    }

    /** 当前生效的更新源地址：本地覆盖优先，否则回落默认常量。 */
    suspend fun getUpdateSourceUrl(): String =
        dataStore.data.first()[Keys.UPDATE_URL] ?: checker.manifestUrl

    suspend fun setUpdateSourceUrl(url: String) =
        dataStore.edit { it[Keys.UPDATE_URL] = url }

    /**
     * 拉取更新清单。
     *
     * 按更新源 URL 分派：`https://api.github.com/...` 走 GitHub Releases 解析
     * （默认源），其余（用户自定义）走原自托管 `version.json` 解析；
     * 两条路径都产出同一个 [UpdateManifest]，上层无需感知。
     */
    suspend fun fetchManifest(): UpdateManifest = checker.fetch(getUpdateSourceUrl())

    /**
     * APK 下载专用 HTTP 客户端（惰性单例）。
     *
     * **为什么是单例**：旧实现每次 `downloadApk` 都 `OkHttpClient.Builder()...build()`，
     * 而一个 OkHttpClient 自带连接池 + Dispatcher 线程池；下载完既不 `shutdown` 也不
     * 复用，等于每下载一次就新起一套线程/连接资源（用户反复点「重试」时尤其明显）。
     * 改为类内惰性单例：连接池与线程池只建一次，重定向、超时参数与旧实现完全一致
     * （GitHub 资产 302 到 release-assets.githubusercontent.com，必须跟随重定向）。
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 下载 APK 到应用私有目录。
     *
     * 进度回调做了节流：只有「累计新增 ≥ 64KB」或「比例推进 ≥ 1%」才回调一次，
     * 避免每个 8KB 缓冲块都切一次线程去刷 UI 状态；循环结束后若最后一块没被阈值
     * 命中，则补发一次 100%，保证进度条能真正走到头。
     */
    suspend fun downloadApk(url: String, onProgress: (DownloadProgress) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val target = apkFile(context)
            val part = File(target.parentFile, target.name + ".part")
            val request = Request.Builder().url(url)
                .header("User-Agent", "Harken-UpdateChecker").build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful || resp.body == null) {
                    throw IOException("HTTP ${resp.code}")
                }
                val total = resp.body!!.contentLength()
                resp.body!!.byteStream().use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var read: Int
                        var done: Long = 0
                        // 上一次已上报的字节数与比例，用于阈值判断（节流状态）。
                        var reportedBytes: Long = 0
                        var reportedFraction = 0f
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            // 长度未知（chunked / 无 Content-Length）时与旧实现一致：不回调。
                            if (total <= 0) continue
                            val fraction = (done.toFloat() / total).coerceIn(0f, 1f)
                            val enoughBytes = done - reportedBytes >= PROGRESS_MIN_BYTES
                            val enoughPercent = fraction - reportedFraction >= PROGRESS_MIN_FRACTION
                            if (enoughBytes || enoughPercent) {
                                reportedBytes = done
                                reportedFraction = fraction
                                onProgress(DownloadProgress(done, total, fraction))
                            }
                        }
                        // 100% 兜底：阈值可能吞掉最后几块（例如尾块既不满 64KB、也没推进
                        // 1%），不补发就会停在 99%。此处在读到文件尾时补一次最终进度。
                        if (total > 0 && reportedBytes != done) {
                            onProgress(DownloadProgress(done, total, 1f))
                        }
                    }
                }
            }
            if (part.exists()) part.renameTo(target)
            target
        }

    fun canInstallPackages(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private object Keys {
        val LAST_CHECK = longPreferencesKey("update.lastCheckMillis")
        val PENDING_CODE = intPreferencesKey("update.pending.code")
        val PENDING_NAME = stringPreferencesKey("update.pending.name")
        val PENDING_URL = stringPreferencesKey("update.pending.url")
        val PENDING_NOTE = stringPreferencesKey("update.pending.note")
        val PENDING_FORCE = booleanPreferencesKey("update.pending.force")
        val UPDATE_URL = stringPreferencesKey("update.sourceUrl")
    }

    suspend fun getLastCheckMillis(): Long =
        dataStore.data.first()[Keys.LAST_CHECK] ?: 0L

    suspend fun setLastCheckMillis(ts: Long) =
        dataStore.edit { it[Keys.LAST_CHECK] = ts }

    suspend fun savePendingUpdate(m: UpdateManifest) = dataStore.edit {
        it[Keys.PENDING_CODE] = m.versionCode
        it[Keys.PENDING_NAME] = m.versionName
        it[Keys.PENDING_URL] = m.apkUrl
        it[Keys.PENDING_NOTE] = m.updateNote
        it[Keys.PENDING_FORCE] = m.force
    }

    suspend fun getPendingUpdate(): UpdateManifest? {
        val map = dataStore.data.first()
        val code = map[Keys.PENDING_CODE] ?: 0
        if (code <= 0) return null
        return UpdateManifest(
            versionCode = code,
            versionName = map[Keys.PENDING_NAME] ?: "",
            apkUrl = map[Keys.PENDING_URL] ?: "",
            updateNote = map[Keys.PENDING_NOTE] ?: "",
            force = map[Keys.PENDING_FORCE] ?: false,
        )
    }

    suspend fun clearPendingUpdate() = dataStore.edit {
        it.remove(Keys.PENDING_CODE)
        it.remove(Keys.PENDING_NAME)
        it.remove(Keys.PENDING_URL)
        it.remove(Keys.PENDING_NOTE)
        it.remove(Keys.PENDING_FORCE)
    }

    companion object {
        /** 下载读写缓冲大小（保持旧实现的 8KB，不改 I/O 行为）。 */
        private const val BUFFER_SIZE = 8 * 1024

        /** 进度回调的最小字节步长：累计新增 64KB 才回调一次。 */
        private const val PROGRESS_MIN_BYTES = 64L * 1024

        /** 进度回调的最小比例步长：比例推进 1% 才回调一次。 */
        private const val PROGRESS_MIN_FRACTION = 0.01f

        fun apkFile(context: Context): File {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(root, "updates").also { it.mkdirs() }
            return File(dir, "harken.apk")
        }
    }
}
