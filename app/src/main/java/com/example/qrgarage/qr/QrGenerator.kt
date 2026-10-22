package com.example.qrgarage.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** ZXing is used for generation only; scanning is ML Kit (section 2). */
object QrGenerator {

    /** Quartile error correction: labels in a garage get dusty, scratched and oily. */
    private val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
        EncodeHintType.MARGIN to 0,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** One cell per module, no quiet zone. */
    fun matrix(content: String): BitMatrix =
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)

    fun bitmap(content: String, sizePx: Int, quietZoneModules: Int = 2): Bitmap {
        val m = matrix(content)
        val modules = m.width + quietZoneModules * 2
        val scale = (sizePx / modules).coerceAtLeast(1)
        val side = modules * scale
        val pixels = IntArray(side * side) { Color.WHITE }
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (!m[x, y]) continue
            val px = (x + quietZoneModules) * scale
            val py = (y + quietZoneModules) * scale
            for (dy in 0 until scale) {
                val row = (py + dy) * side
                for (dx in 0 until scale) pixels[row + px + dx] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    /** Draws crisp vector modules into [canvas] (used for PDF labels). */
    fun draw(canvas: Canvas, content: String, left: Float, top: Float, size: Float) {
        val m = matrix(content)
        val cell = size / m.width
        val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false; style = Paint.Style.FILL }
        for (y in 0 until m.height) {
            var x = 0
            while (x < m.width) {
                if (!m[x, y]) { x++; continue }
                val start = x
                while (x < m.width && m[x, y]) x++
                // Slight overlap avoids hairline gaps between adjacent modules in PDF viewers.
                canvas.drawRect(
                    left + start * cell, top + y * cell,
                    left + x * cell + 0.05f, top + (y + 1) * cell + 0.05f, paint,
                )
            }
        }
    }
}
