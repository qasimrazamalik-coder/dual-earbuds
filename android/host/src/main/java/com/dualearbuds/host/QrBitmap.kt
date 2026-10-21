package com.dualearbuds.host

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal object QrBitmap {
    /** Black-on-white QR code of [text], [sizePx] square, with a one-module quiet zone. */
    fun create(text: String, sizePx: Int = 720): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = m.width
            val h = m.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) pixels[row + x] = if (m.get(x, y)) BLACK else WHITE
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            null
        }
    }

    private const val BLACK = 0xFF0B1220.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
