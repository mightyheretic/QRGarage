package com.example.qrgarage.qr

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.OutputStream
import kotlin.math.min

private const val PT_PER_IN = 72f
private const val PT_PER_MM = 72f / 25.4f

enum class Paper(val label: String, val widthPt: Float, val heightPt: Float) {
    LETTER("Letter", 8.5f * PT_PER_IN, 11f * PT_PER_IN),
    A4("A4", 210f * PT_PER_MM, 297f * PT_PER_MM),
}

/**
 * SET-QR-4: a label sheet geometry, all lengths in PDF points (1/72 in). Presets mimic common
 * Avery sheets; [custom] builds an evenly spaced grid for anything else.
 */
data class LabelSheetSpec(
    val id: String,
    val name: String,
    val paper: Paper,
    val columns: Int,
    val rows: Int,
    val labelWidth: Float,
    val labelHeight: Float,
    val marginLeft: Float,
    val marginTop: Float,
    val gapX: Float,
    val gapY: Float,
) {
    val perSheet get() = columns * rows

    companion object {
        val PRESETS = listOf(
            LabelSheetSpec(
                "avery-22806", "Letter · 2×2 in square, 12/sheet (Avery 22806-style)", Paper.LETTER,
                columns = 3, rows = 4, labelWidth = 2f * PT_PER_IN, labelHeight = 2f * PT_PER_IN,
                marginLeft = 0.625f * PT_PER_IN, marginTop = 0.625f * PT_PER_IN,
                gapX = 0.625f * PT_PER_IN, gapY = 0.5833f * PT_PER_IN,
            ),
            LabelSheetSpec(
                "avery-5160", "Letter · 1×2⅝ in address, 30/sheet (Avery 5160-style)", Paper.LETTER,
                columns = 3, rows = 10, labelWidth = 2.625f * PT_PER_IN, labelHeight = 1f * PT_PER_IN,
                marginLeft = 0.1875f * PT_PER_IN, marginTop = 0.5f * PT_PER_IN,
                gapX = 0.125f * PT_PER_IN, gapY = 0f,
            ),
            LabelSheetSpec(
                "avery-l7160", "A4 · 63.5×38.1 mm, 21/sheet (Avery L7160-style)", Paper.A4,
                columns = 3, rows = 7, labelWidth = 63.5f * PT_PER_MM, labelHeight = 38.1f * PT_PER_MM,
                marginLeft = 7.2f * PT_PER_MM, marginTop = 15.15f * PT_PER_MM,
                gapX = 2.5f * PT_PER_MM, gapY = 0f,
            ),
            LabelSheetSpec(
                "a4-60mm", "A4 · 60 mm square, 12/sheet", Paper.A4,
                columns = 3, rows = 4, labelWidth = 60f * PT_PER_MM, labelHeight = 60f * PT_PER_MM,
                marginLeft = 10f * PT_PER_MM, marginTop = 15f * PT_PER_MM,
                gapX = 5f * PT_PER_MM, gapY = 9f * PT_PER_MM,
            ),
        )

        val DEFAULT = PRESETS.first()

        fun byId(id: String?) = PRESETS.firstOrNull { it.id == id } ?: DEFAULT

        /** An evenly spaced grid with 10 mm page margins and 4 mm gutters. */
        fun custom(paper: Paper, columns: Int, rows: Int): LabelSheetSpec {
            val cols = columns.coerceIn(1, 10)
            val rws = rows.coerceIn(1, 20)
            val margin = 10f * PT_PER_MM
            val gap = 4f * PT_PER_MM
            return LabelSheetSpec(
                id = "custom", name = "Custom ${cols}×$rws on ${paper.label}", paper = paper,
                columns = cols, rows = rws,
                labelWidth = (paper.widthPt - 2 * margin - (cols - 1) * gap) / cols,
                labelHeight = (paper.heightPt - 2 * margin - (rws - 1) * gap) / rws,
                marginLeft = margin, marginTop = margin, gapX = gap, gapY = gap,
            )
        }
    }
}

data class LabelContent(val payload: String, val name: String, val path: String, val code: String)

/** SET-QR-2: renders a printable PDF label sheet with name and path under each code. */
object LabelPdfWriter {

    fun write(labels: List<LabelContent>, spec: LabelSheetSpec, out: OutputStream) {
        val doc = PdfDocument()
        try {
            labels.chunked(spec.perSheet).forEachIndexed { pageIndex, pageLabels ->
                val info = PdfDocument.PageInfo.Builder(
                    spec.paper.widthPt.toInt(), spec.paper.heightPt.toInt(), pageIndex + 1,
                ).create()
                val page = doc.startPage(info)
                pageLabels.forEachIndexed { i, label ->
                    val col = i % spec.columns
                    val row = i / spec.columns
                    val left = spec.marginLeft + col * (spec.labelWidth + spec.gapX)
                    val top = spec.marginTop + row * (spec.labelHeight + spec.gapY)
                    drawLabel(page.canvas, label, left, top, spec.labelWidth, spec.labelHeight)
                }
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun drawLabel(
        canvas: android.graphics.Canvas, label: LabelContent,
        left: Float, top: Float, w: Float, h: Float,
    ) {
        val pad = min(w, h) * 0.08f
        val wide = w / h >= 1.6f
        if (wide) {
            // QR on the left, text to the right.
            val qr = h - 2 * pad
            QrGenerator.draw(canvas, label.payload, left + pad, top + pad, qr)
            val textLeft = left + pad * 2 + qr
            drawTextBlock(canvas, label, textLeft, top + pad, w - (textLeft - left) - pad, h - 2 * pad, qr, Layout.Alignment.ALIGN_NORMAL)
        } else {
            // QR on top, name and path printed under it.
            val textHeight = h * 0.30f
            val qr = min(w - 2 * pad, h - 2 * pad - textHeight)
            QrGenerator.draw(canvas, label.payload, left + (w - qr) / 2, top + pad, qr)
            drawTextBlock(canvas, label, left + pad, top + pad + qr + pad * 0.5f, w - 2 * pad, textHeight, qr, Layout.Alignment.ALIGN_CENTER)
        }
    }

    private fun drawTextBlock(
        canvas: android.graphics.Canvas, label: LabelContent,
        x: Float, y: Float, width: Float, height: Float, qrSize: Float, align: Layout.Alignment,
    ) {
        val base = (qrSize * 0.14f).coerceIn(6f, 16f)
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = base; typeface = Typeface.DEFAULT_BOLD
        }
        val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY; textSize = base * 0.72f
        }
        val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = base * 0.72f; typeface = Typeface.MONOSPACE
        }
        var cursor = y
        val maxY = y + height
        for ((text, paint, lines) in listOf(
            Triple(label.name, namePaint, 2),
            Triple(label.path, smallPaint, 2),
            Triple(label.code, codePaint, 1),
        )) {
            if (text.isBlank()) continue
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
                .setAlignment(align)
                .setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
            if (cursor + layout.height > maxY + 1) break
            canvas.save()
            canvas.translate(x, cursor)
            layout.draw(canvas)
            canvas.restore()
            cursor += layout.height + base * 0.2f
        }
    }
}
