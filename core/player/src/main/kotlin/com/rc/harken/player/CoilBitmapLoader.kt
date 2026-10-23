// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.util.BitmapLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知栏封面加载器（架构文档 §3：通知栏封面走 Coil）。
 *
 * media3 1.5.1 的 [BitmapLoader] 有三个抽象成员（`loadBitmap(Uri)` /
 * `decodeBitmap(byte[])` / `supportsMimeType`），均为同步 future 接口；Coil
 * 的加载本身是挂起的，故在内部 IO scope 中加载后回填 future。失败以异常
 * 完成 future（通知降级为无封面），绝不影响播放。
 */
class CoilBitmapLoader(private val context: Context) : BitmapLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = loadAsync(uri)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = loadAsync(data)

    /** 封面均为图片 MIME；其余交给默认处理。 */
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun loadAsync(data: Any?): ListenableFuture<Bitmap> {
        val result = SettableFuture.create<Bitmap>()
        scope.launch {
            try {
                val bitmap = load(data)
                if (bitmap != null) {
                    result.set(bitmap)
                } else {
                    result.setException(IOException("failed to load artwork"))
                }
            } catch (error: Exception) {
                result.setException(error)
            }
        }
        return result
    }

    private suspend fun load(data: Any?): Bitmap? {
        if (data == null) return null
        val request = ImageRequest.Builder(context)
            .data(data)
            // 通知栏位图不能是硬件加速位图。
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        return (result as? SuccessResult)?.drawable?.toBitmap()
    }
}
