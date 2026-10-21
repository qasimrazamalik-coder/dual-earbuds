package com.dualearbuds.host.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** One module per cell, 2-module quiet zone. Null when the text can't be encoded. */
internal fun encodeQr(text: String): BitMatrix? = try {
    QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    )
} catch (_: Throwable) {
    null
}

/**
 * QR code drawn from theme colours. Scanners need dark modules on a light plate, so in a dark
 * theme the plate uses onSurface and the modules surface (and the reverse in a light theme).
 */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { encodeQr(text) } ?: return
    val cs = MaterialTheme.colorScheme
    val darkTheme = cs.surface.luminance() < 0.5f
    val plate = if (darkTheme) cs.onSurface else cs.surface
    val ink = if (darkTheme) cs.surface else cs.onSurface
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = "QR code of the listening link" }) {
        drawRect(plate)
        val n = matrix.width
        val cell = size.minDimension / n
        // Round cells outward a hair so neighbouring modules don't show seams.
        val sz = Size(cell + 0.5f, cell + 0.5f)
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix.get(x, y)) drawRect(ink, topLeft = Offset(x * cell, y * cell), size = sz)
            }
        }
    }
}
