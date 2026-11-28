package com.taocrypt.micmask.ui2

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.taocrypt.micmask.R
import androidx.compose.ui.platform.LocalContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.taocrypt.micmask.tr

/*
 * 传音二维码配对（2.1.1-alpha.13）：
 *  - 接收端：[QrImage] 用 zxing core 把 micmask://link?h=…&c=…（精简写法）画成二维码（2.1.1-alpha.14 起纠错 H、圆角白底卡片、中间 App 图标；深色主题下仍是白底深色块，保证能扫）；
 *  - 发送端：[rememberQrScanner] 先按需申请相机权限，拒绝就提示手填；同意后打开 zxing-android-embedded 的扫码界面，只认 QR。
 */

/**
 * 二维码矩阵（内容变了才重算）；内容过长等异常返回 null。
 * 2.1.1-alpha.14：内容改成精简写法（约 40–90 字符），纠错提到 H（可遮挡约 30%），中间放 logo 后仍可扫；外边距由 [QrImage] 自己画，这里 0。
 */
internal fun qrMatrix(text: String, ec: ErrorCorrectionLevel = ErrorCorrectionLevel.H): BitMatrix? = runCatching {
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(
        EncodeHintType.ERROR_CORRECTION to ec, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"))
}.getOrNull()

/**
 * 二维码（2.1.1-alpha.14 美化）：圆角白底卡片 + 2 格留白（深浅色主题都是白底深色块，保证扫码对比度）；
 * 数据点画成小圆角方块，三个定位角画成圆角框 + 圆角实心块；中间放 App 图标（float_logo，约占边长 20%，
 * 下面垫白色圆角底并清掉这块数据点——H 级纠错可容纳），logo 区域不超过约 7% 的码面积。
 */
@Composable
internal fun QrImage(text: String, modifier: Modifier = Modifier) {
    val m = remember(text) { qrMatrix(text) } ?: return
    val logo = ImageBitmap.imageResource(R.drawable.float_logo)
    val dark = Color(0xFF1B1530)          // 接近图标底色的深紫黑，扫码对比度与纯黑相当
    Canvas(modifier.aspectRatio(1f)) {
        val n = m.width
        val quiet = 2                      // 留白格数（含在卡片里）
        val cell = size.minDimension / (n + quiet * 2)
        val off = cell * quiet
        // 圆角白底
        drawRoundRect(Color.White, cornerRadius = CornerRadius(cell * 2.2f))
        // logo 占位：居中、奇数格宽，约 20% 边长
        var lg = (n * 0.20f).toInt().coerceAtLeast(5); if (lg % 2 == 0) lg++
        val l0 = (n - lg) / 2; val l1 = l0 + lg
        fun inFinder(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
        fun inLogo(x: Int, y: Int) = x in l0 until l1 && y in l0 until l1
        val r = CornerRadius(cell * 0.32f)
        val inset = cell * 0.06f
        for (y in 0 until n) for (x in 0 until n) {
            if (!m[x, y] || inFinder(x, y) || inLogo(x, y)) continue
            drawRoundRect(dark, Offset(off + x * cell + inset, off + y * cell + inset), Size(cell - inset * 2, cell - inset * 2), r)
        }
        // 定位角：7×7 圆角外框（1 格粗）+ 3×3 圆角实心
        fun finder(fx: Int, fy: Int) {
            val o = Offset(off + fx * cell, off + fy * cell)
            drawRoundRect(dark, Offset(o.x + cell / 2, o.y + cell / 2), Size(cell * 6, cell * 6), CornerRadius(cell * 1.6f), style = Stroke(width = cell))
            drawRoundRect(dark, Offset(o.x + cell * 2, o.y + cell * 2), Size(cell * 3, cell * 3), CornerRadius(cell * 0.9f))
        }
        finder(0, 0); finder(n - 7, 0); finder(0, n - 7)
        // logo：白色圆角垫底 + 图标
        val lx = off + l0 * cell; val lw = lg * cell
        drawRoundRect(Color.White, Offset(lx, lx), Size(lw, lw), CornerRadius(lw * 0.24f))
        val pad = lw * 0.08f
        drawImage(logo, srcOffset = IntOffset.Zero, srcSize = IntSize(logo.width, logo.height),
            dstOffset = IntOffset((lx + pad).toInt(), (lx + pad).toInt()), dstSize = IntSize((lw - pad * 2).toInt(), (lw - pad * 2).toInt()))
    }
}

private fun hasCamera(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * 返回「开始扫码」函数。[onText] 收到扫描结果文字；用户取消时不回调；相机权限被拒时回调 [onDenied]（界面提示改为手填）。
 */
@Composable
internal fun rememberQrScanner(onText: (String) -> Unit, onDenied: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.takeIf { it.isNotEmpty() }?.let(onText) }
    fun launch() = scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(tr("对准接收端「传音」页上的二维码"))
        .setBeepEnabled(false).setOrientationLocked(false))
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) launch() else onDenied() }
    return { if (hasCamera(ctx)) launch() else perm.launch(android.Manifest.permission.CAMERA) }
}
