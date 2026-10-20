// Ported from kumone by missuo (LGPL-3.0)
//
// 扫一扫（我的页快捷入口，覆盖层页面）。
//
// 纯本地能力：CameraX 取景 + zxing 解码，**不调用任何业务接口**（与 iOS 侧扫码一致）。
//
// 页面状态机（`when` 互斥分支，切换分支即解绑/重绑相机）：
//   无权限           → 说明文案 + 「去设置授权」（跳系统应用详情页），不空白不崩溃
//   有权限 / 相机失败 → 提示文案 + 「重试」
//   有权限 / 取景中   → PreviewView 预览 + 取景框 + 自动解码
//   已出结果         → 结果文本 + 复制 / 浏览器打开 / 重新扫描
//
// 相机链路要点：
//  - `ImageAnalysis` 用 `STRATEGY_KEEP_ONLY_LATEST`：解码跟不上时丢旧帧，不排队卡顿；
//  - 解码在**进程级共享单线程**执行，结果投递回主线程（避免阻塞 UI，也避免每次进入
//    页面新建/销毁线程，更避免解绑瞬间向已关闭执行器投递任务而抛
//    `RejectedExecutionException`）；
//  - `ImageProxy.close()` 必须在 `finally` 中调用，否则相机不再投递下一帧；
//  - 解码成功由 `AtomicBoolean` 兜底，保证只回调一次结果。

package im.missuo.kumone.feature.library.pages

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import im.missuo.kumone.design.theme.BrandRed
import im.missuo.kumone.design.theme.GlassTopAppBar
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicBoolean

/** 分析帧的目标分辨率（720p 足够解码二维码/条码，且单帧解码耗时可控）。 */
private const val TARGET_ANALYSIS_WIDTH = 1280
private const val TARGET_ANALYSIS_HEIGHT = 720

/**
 * 解码执行器：进程级共享的常驻单线程（daemon 线程）。
 *
 * 刻意**不做** per-page shutdown：相机在解绑瞬间仍可能向执行器投递最后一帧，
 * 若执行器已 `shutdown()` 会抛 `RejectedExecutionException` 打到相机线程上；
 * 常驻单线程既规避该风险，也避免每次进入页面创建/销毁线程。
 */
private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "kumone-scan-decode").apply { isDaemon = true }
}

/** zxing 解码提示：覆盖二维码与常见一维/二维码，并强制 UTF-8 文本。 */
private val DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.AZTEC,
        BarcodeFormat.PDF_417,
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93,
        BarcodeFormat.CODABAR,
        BarcodeFormat.ITF,
        BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A,
        BarcodeFormat.UPC_E,
    ),
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.CHARACTER_SET to "UTF-8",
)

/**
 * 扫一扫页面。
 *
 * @param onBack 返回上一页（顶栏返回键）。
 * @param modifier 外层修饰符。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current

    var hasPermission by remember { mutableStateOf(isCameraPermissionGranted(context)) }
    var scanResult by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    // 从系统设置页返回时重新读取授权状态（用户可能刚在设置里打开相机权限）。
    // `ACTION_APPLICATION_DETAILS_SETTINGS` 不保证返回时携带可判定的 resultCode，
    // 故不依赖 resultCode，回调即重新查询真实权限。
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { hasPermission = isCameraPermissionGranted(context) }

    // 进入页面即申请一次相机权限（已授权则不重复弹窗）。
    LaunchedEffect(Unit) {
        if (!isCameraPermissionGranted(context)) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text(text = "扫一扫") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
        // 注意：不使用 Scaffold 的 bottomBar —— 本页是全屏取景，底部不能有常驻空白带。
    ) { padding ->
        Box(
            modifier = modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val currentResult = scanResult
            val currentError = cameraError
            when {
                // 1) 相机启动/绑定失败（含「设备无可用相机」）。
                currentError != null -> ScanMessagePanel(
                    icon = Icons.Filled.WarningAmber,
                    title = "相机不可用",
                    body = currentError,
                    actionText = "重试",
                    onAction = { cameraError = null },
                )
                // 2) 已出结果。
                currentResult != null -> ScanResultPanel(
                    result = currentResult,
                    onCopy = { text ->
                        copyToClipboard(context, text)
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    },
                    onOpenInBrowser = { url -> openInBrowser(context, url) },
                    onRescan = { scanResult = null },
                )
                // 3) 已授权 → 取景 + 解码。
                hasPermission -> CameraScanView(
                    onResult = { text ->
                        scanResult = text
                        // 轻震动反馈：CONTEXT_CLICK 无需 VIBRATE 权限（minSdk 26 起可用）。
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    },
                    onError = { message -> cameraError = message },
                )
                // 4) 未授权 → 说明 + 去设置授权。
                else -> ScanMessagePanel(
                    icon = Icons.Filled.CameraAlt,
                    title = "需要相机权限",
                    body = "扫一扫需要访问相机才能识别二维码 / 条码。请在系统设置中授予「相机」权限后重试。",
                    actionText = "去设置授权",
                    onAction = { settingsLauncher.launch(appDetailsIntent(context)) },
                )
            }
        }
    }
}

// MARK: - 取景 + 解码

/**
 * 相机取景视图：`PreviewView` 预览 + `ImageAnalysis` 帧解码。
 *
 * @param onResult 解码成功回调（**保证只回调一次**，在主线程执行）。
 * @param onError 相机初始化/绑定失败回调（在主线程执行）。
 */
@Composable
private fun CameraScanView(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    val previewView = remember(context) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    // 用 CameraX 的协程扩展 `awaitInstance` 绑定相机，**不用** `getInstance()`。
    //
    // 原因：`getInstance()` 返回 `com.google.guava:listenablefuture` 的 `ListenableFuture`，
    // 而本项目 guava 只在 runtime 作用域、`listenablefuture` 又被版本对齐成空壳
    // `9999.0-empty-to-avoid-conflict-with-guava`，编译期拿不到该类型。
    // `awaitInstance` 的签名只涉及 `ProcessCameraProvider`，编译期无此依赖问题。
    LaunchedEffect(lifecycleOwner, previewView) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }
            .getOrElse { throwable ->
                onError(throwable.message ?: throwable.javaClass.simpleName)
                return@LaunchedEffect
            }
        val bound = runCatching {
            // 先解绑，避免重复进入页面时叠加用例。
            provider.unbindAll()

            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysisUseCase = ImageAnalysis.Builder()
                .setTargetResolution(
                    android.util.Size(TARGET_ANALYSIS_WIDTH, TARGET_ANALYSIS_HEIGHT),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(analyzerExecutor, BarcodeAnalyzer(mainExecutor, onResult))
                }
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                previewUseCase,
                analysisUseCase,
            )
        }
        if (bound.isFailure) {
            onError(bound.exceptionOrNull()?.message ?: "相机启动失败")
            return@LaunchedEffect
        }
        // 挂起到页面销毁；finally 中解绑所有用例（等价于原 DisposableEffect 的 onDispose）。
        try {
            awaitCancellation()
        } finally {
            runCatching { provider.unbindAll() }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ScanFrameOverlay()
    }
}

/**
 * zxing 帧解码器。
 *
 * 直接把 `YUV_420_888` 的 Y 平面转成 NV21 亮度源（不经过 Bitmap）：
 *  - 省掉逐帧「YUV→ARGB 位图」的分配与转换（720p 每帧约 3.7MB），显著降低 GC 压力；
 *  - zxing 只需亮度信息，色度平面仅在需要旋转时一并搬运。
 *
 * 解码成功后置位 [finished]，保证结果只回调一次。
 */
private class BarcodeAnalyzer(
    private val callbackExecutor: Executor,
    private val onResult: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader()
    private val finished = AtomicBoolean(false)

    init {
        reader.setHints(DECODE_HINTS)
    }

    override fun analyze(imageProxy: ImageProxy) {
        try {
            if (finished.get()) return
            val source = runCatching { imageProxy.toLuminanceSource() }.getOrNull() ?: return
            val text = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            }.getOrNull()
            if (!text.isNullOrEmpty() && finished.compareAndSet(false, true)) {
                // 投递回主线程，避免在解码线程上写 Compose 状态。
                callbackExecutor.execute { onResult(text) }
            }
        } finally {
            // 必须关闭，否则相机不会投递下一帧（预览会卡在首帧）。
            imageProxy.close()
        }
    }
}

/** 把 `ImageProxy` 的 YUV 帧按旋转角修正后转成 zxing 亮度源。 */
private fun ImageProxy.toLuminanceSource(): LuminanceSource? {
    if (format != ImageFormat.YUV_420_888) return null
    if (planes.size < 3) return null
    val frameWidth = width
    val frameHeight = height
    // NV21 打包要求偶数边长（色度平面为 1/2 采样）；相机实际输出恒为偶数，此处仅兜底。
    if (frameWidth % 2 != 0 || frameHeight % 2 != 0) return null

    val nv21 = toNv21(frameWidth, frameHeight) ?: return null
    val rotation = ((imageInfo.rotationDegrees % 360) + 360) % 360
    if (rotation == 0) {
        return PlanarYUVLuminanceSource(
            nv21, frameWidth, frameHeight, 0, 0, frameWidth, frameHeight, false,
        )
    }
    val rotated = rotateNv21(nv21, frameWidth, frameHeight, rotation) ?: return null
    val rotatedWidth = if (rotation % 180 == 0) frameWidth else frameHeight
    val rotatedHeight = if (rotation % 180 == 0) frameHeight else frameWidth
    return PlanarYUVLuminanceSource(
        rotated, rotatedWidth, rotatedHeight, 0, 0, rotatedWidth, rotatedHeight, false,
    )
}

/**
 * `YUV_420_888` → NV21（V/U 交错）字节数组。
 *
 * 逐行按 `pixelStride` 取样，因此同时兼容两种设备实现：
 *  - `pixelStride == 1`（planar / I420）；
 *  - `pixelStride == 2`（semi-planar / NV12），此时 U、V 平面共享同一段交错色度区。
 *
 * @return NV21 字节数组；尺寸非法时返回 null。
 */
private fun ImageProxy.toNv21(frameWidth: Int, frameHeight: Int): ByteArray? {
    val chromaWidth = frameWidth / 2
    val chromaHeight = frameHeight / 2

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    // 缓冲区 position 可能被上一帧的分析挪动过，先复位到起点。
    yBuffer.rewind()
    uBuffer.rewind()
    vBuffer.rewind()

    val nv21 = ByteArray(frameWidth * frameHeight + chromaWidth * chromaHeight * 2)

    // Y 平面：逐行取样，跳过行尾 padding（rowStride 可能 > frameWidth）。
    var out = 0
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    for (row in 0 until frameHeight) {
        var src = row * yRowStride
        for (col in 0 until frameWidth) {
            nv21[out++] = yBuffer.get(src)
            src += yPixelStride
        }
    }

    // 色度平面：NV21 要求 V/U 交错，故先写 V 再写 U。
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride
    for (row in 0 until chromaHeight) {
        var uSrc = row * uRowStride
        var vSrc = row * vRowStride
        for (col in 0 until chromaWidth) {
            nv21[out++] = vBuffer.get(vSrc)
            nv21[out++] = uBuffer.get(uSrc)
            uSrc += uPixelStride
            vSrc += vPixelStride
        }
    }
    return nv21
}

/**
 * 按 [rotation]（90 / 180 / 270）旋转 NV21 数据。
 *
 * 色度以 V/U 成对搬运，保证交错关系不错位。
 *
 * @return 旋转后的数据；[rotation] 不受支持时返回 null。
 */
private fun rotateNv21(data: ByteArray, width: Int, height: Int, rotation: Int): ByteArray? {
    val frameSize = width * height
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val output = ByteArray(data.size)
    var out = 0

    fun putPair(row: Int, col: Int) {
        val index = frameSize + (row * chromaWidth + col) * 2
        output[out++] = data[index]
        output[out++] = data[index + 1]
    }

    when (rotation) {
        // 顺时针 90°：Y 与色度均按「列正序、行倒序」搬运。
        90 -> {
            for (col in 0 until width) {
                for (row in height - 1 downTo 0) output[out++] = data[row * width + col]
            }
            for (col in 0 until chromaWidth) {
                for (row in chromaHeight - 1 downTo 0) putPair(row, col)
            }
        }
        // 180°：逐行整体倒序。
        180 -> {
            for (row in height - 1 downTo 0) {
                val base = row * width
                for (col in width - 1 downTo 0) output[out++] = data[base + col]
            }
            for (row in chromaHeight - 1 downTo 0) {
                for (col in chromaWidth - 1 downTo 0) putPair(row, col)
            }
        }
        // 顺时针 270°（= 逆时针 90°）。
        270 -> {
            for (col in width - 1 downTo 0) {
                for (row in 0 until height) output[out++] = data[row * width + col]
            }
            for (col in chromaWidth - 1 downTo 0) {
                for (row in 0 until chromaHeight) putPair(row, col)
            }
        }
        else -> return null
    }
    return output
}

// MARK: - 取景框

/** 取景框遮罩 + 边框 + 底部提示文案（纯绘制，不拦截触摸）。 */
@Composable
private fun ScanFrameOverlay(modifier: Modifier = Modifier) {
    val maskColor = Color.Black.copy(alpha = 0.45f)
    val frameColor = Color.White

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val frameSide = minOf(maxWidth, maxHeight) * 0.62f
        val frameLeft = (maxWidth - frameSide) / 2
        val frameTop = (maxHeight - frameSide) / 2

        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = frameLeft.toPx()
            val top = frameTop.toPx()
            val side = frameSide.toPx()
            val canvasWidth = size.width
            val canvasHeight = size.height

            // 取景框之外压暗。
            drawRect(maskColor, topLeft = Offset(0f, 0f), size = Size(canvasWidth, top))
            drawRect(
                maskColor,
                topLeft = Offset(0f, top + side),
                size = Size(canvasWidth, (canvasHeight - top - side).coerceAtLeast(0f)),
            )
            drawRect(maskColor, topLeft = Offset(0f, top), size = Size(left, side))
            drawRect(
                maskColor,
                topLeft = Offset(left + side, top),
                size = Size((canvasWidth - left - side).coerceAtLeast(0f), side),
            )
            // 取景框描边。
            drawRoundRect(
                color = frameColor,
                topLeft = Offset(left, top),
                size = Size(side, side),
                cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        Text(
            text = "将二维码 / 条码放入取景框内，将自动识别",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = frameTop + frameSide + 20.dp)
                .padding(horizontal = 32.dp),
        )
    }
}

// MARK: - 结果态 / 提示态

/** 扫码结果面板：文本 + 复制 / 浏览器打开 / 重新扫描。 */
@Composable
private fun ScanResultPanel(
    result: String,
    onCopy: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUrl = isProbablyUrl(result)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.QrCodeScanner,
            contentDescription = null,
            tint = BrandRed,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "扫描结果",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Text(
                text = result,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { onCopy(result) },
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(text = "复制")
            }
            if (isUrl) {
                OutlinedButton(onClick = { onOpenInBrowser(result) }) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(text = "用浏览器打开")
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onRescan) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(text = "重新扫描")
        }
    }
}

/** 通用提示面板（无权限 / 相机不可用）。 */
@Composable
private fun ScanMessagePanel(
    icon: ImageVector,
    title: String,
    body: String,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
            shape = RoundedCornerShape(50),
        ) {
            Text(text = actionText)
        }
    }
}

// MARK: - 平台辅助

/** 是否已获得相机权限。 */
private fun isCameraPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** 本应用的系统设置详情页 Intent。 */
private fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }

/** 复制文本到系统剪贴板。 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("scan_result", text))
}

/** 以浏览器打开链接（无可用浏览器时静默失败，不崩溃）。 */
private fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE),
        )
    }
}

/** 粗略判定扫码文本是否为可打开的 URL。 */
private fun isProbablyUrl(text: String): Boolean {
    val lower = text.lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
}
