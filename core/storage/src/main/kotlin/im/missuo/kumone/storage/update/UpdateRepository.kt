// Ported from kumone by missuo (LGPL-3.0)
//
// 在线检测更新仓储：已装版本读取、清单拉取、APK 下载落盘、安装跳转，
// 以及「待更新」状态的 DataStore 持久化（跨进程重启保留）。

package im.missuo.kumone.storage.update

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
import im.missuo.kumone.net.update.UpdateChecker
import im.missuo.kumone.net.update.UpdateManifest
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

    suspend fun fetchManifest(): UpdateManifest = checker.fetchManifest()

    suspend fun downloadApk(url: String, onProgress: (DownloadProgress) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val target = apkFile(context)
            val part = File(target.parentFile, target.name + ".part")
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(url)
                .header("User-Agent", "Kumone-UpdateChecker").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful || resp.body == null) {
                    throw IOException("HTTP ${resp.code}")
                }
                val total = resp.body!!.contentLength()
                resp.body!!.byteStream().use { input ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var done: Long = 0
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) onProgress(DownloadProgress(done, total, done.toFloat() / total))
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
        fun apkFile(context: Context): File {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(root, "updates").also { it.mkdirs() }
            return File(dir, "kumone.apk")
        }
    }
}
