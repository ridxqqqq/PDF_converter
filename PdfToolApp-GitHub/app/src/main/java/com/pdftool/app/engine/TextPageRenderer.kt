package com.pdftool.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint

/**
 * Renders plain text into a list of A4-sized bitmaps, one per page, using the
 * device's own fonts. This guarantees correct rendering of CJK (Chinese/Japanese/
 * Korean) characters without bundling any TTF font, and avoids the 14 standard
 * PDF fonts which cannot display Chinese.
 *
 * The resulting PDFs are image-based (text is not selectable). This is an
 * intentional, reliable trade-off for the "text → PDF" direction.
 */
object TextPageRenderer {

    private const val DPI = 150
    private const val A4_WIDTH_IN = 8.27f
    private const val A4_HEIGHT_IN = 11.69f
    private const val MARGIN_IN = 0.7f

    data class RenderedPage(val bitmap: Bitmap, val widthPt: Float, val heightPt: Float)

    fun render(text: String): List<RenderedPage> {
        val pageW = (A4_WIDTH_IN * DPI).toInt()
        val pageH = (A4_HEIGHT_IN * DPI).toInt()
        val margin = (MARGIN_IN * DPI).toInt()

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f * (DPI / 72f)        // ~25px
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val lineHeight = (paint.textSize * 1.5f).toInt()
        val maxTextW = pageW - 2 * margin
        val lines = wrap(text, paint, maxTextW)
        val maxLines = (pageH - 2 * margin) / lineHeight

        val pages = mutableListOf<RenderedPage>()
        var i = 0
        while (i < lines.size) {
            val end = minOf(i + maxLines, lines.size)
            val pageLines = lines.subList(i, end)
            val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            var y = (margin + paint.textSize)
            for (ln in pageLines) {
                c.drawText(ln, margin.toFloat(), y, paint)
                y += lineHeight
            }
            pages.add(RenderedPage(bmp, A4_WIDTH_IN * 72f, A4_HEIGHT_IN * 72f))
            i = end
        }
        if (pages.isEmpty()) {
            val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.WHITE)
            pages.add(RenderedPage(bmp, A4_WIDTH_IN * 72f, A4_HEIGHT_IN * 72f))
        }
        return pages
    }

    /** Greedy character-level wrapping that also honors explicit newlines. */
    private fun wrap(text: String, paint: TextPaint, maxW: Int): List<String> {
        val result = mutableListOf<String>()
        for (para in text.split('\n')) {
            if (para.isEmpty()) {
                result.add("")
                continue
            }
            val line = StringBuilder()
            var lineW = 0f
            for (ch in para.toCharArray()) {
                val w = paint.measureText(ch.toString())
                if (lineW + w > maxW && line.isNotEmpty()) {
                    result.add(line.toString())
                    line.setLength(0)
                    lineW = 0f
                }
                line.append(ch)
                lineW += w
            }
            result.add(line.toString())
        }
        return result
    }
}
