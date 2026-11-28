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
import androidx.compose.ui.graphics.Color
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
 *  - 接收端：[QrImage] 用 zxing core 把 micmask://link?… 画成二维码（纠错 M、留 2 格白边，白底黑块，深色主题下也能扫）；
 *  - 发送端：[rememberQrScanner] 先按需申请相机权限，拒绝就提示手填；同意后打开 zxing-android-embedded 的扫码界面，只认 QR。
 */

/** 二维码矩阵（内容变了才重算）；内容过长等异常返回 null */
internal fun qrMatrix(text: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
}.getOrNull()

@Composable
internal fun QrImage(text: String, modifier: Modifier = Modifier) {
    val m = remember(text) { qrMatrix(text) } ?: return
    Canvas(modifier.aspectRatio(1f)) {
        drawRect(Color.White)
        val cell = size.minDimension / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y])
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
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
