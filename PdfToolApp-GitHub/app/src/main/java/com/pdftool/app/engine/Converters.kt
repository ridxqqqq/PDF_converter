package com.pdftool.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.net.Uri
import com.pdftool.app.model.ConversionType
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A single conversion step. Implementations do blocking I/O and are invoked on
 * Dispatchers.IO by the UI layer. Inputs/outputs are SAF [Uri]s resolved through
 * the content resolver, so no file-path or storage-permission juggling is needed.
 */
interface Converter {
    val type: ConversionType
    fun convert(context: Context, inputs: List<Uri>, output: Uri)
    /** Optional hook for converters that need UI-provided options. Default: ignore. */
    fun setOptions(options: Bundle) {}
}

/** Internal: a rendered page ready to be drawn into a PDF. */
private data class PdfPage(val bitmap: Bitmap, val wPt: Float, val hPt: Float)

// =================================================================== helpers
private fun renderPdfPages(context: Context, uri: Uri, dpi: Float): List<Bitmap> {
    IO.input(context, uri).use { `is` ->
        PDDocument.load(`is`).use { doc ->
            val renderer = PDFRenderer(doc)
            return (0 until doc.numberOfPages).map { i ->
                renderer.renderImageWithDPI(i, dpi, ImageType.RGB)
            }
        }
    }
}

private fun extractPdfText(context: Context, uri: Uri): String {
    IO.input(context, uri).use { `is` ->
        PDDocument.load(`is`).use { doc -> return PDFTextStripper().getText(doc) }
    }
}

private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
    if (src.width == targetW) return src
    val h = (src.height * targetW / src.width.toFloat()).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, targetW, h, true).also {
        if (it != src) src.recycle()
    }
}

private fun saveBitmapsAsPdf(pages: List<PdfPage>, out: java.io.OutputStream) {
    PDDocument().use { doc ->
        for (p in pages) {
            val page = PDPage(PDRectangle(p.wPt, p.hPt))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                val img = LosslessFactory.createFromImage(doc, p.bitmap)
                cs.drawImage(img, 0f, 0f, p.wPt, p.hPt)
            }
            p.bitmap.recycle()
        }
        doc.save(out)
    }
}

private fun ByteArray.toPngBitmap(): Bitmap =
    BitmapFactory.decodeByteArray(this, 0, this.size)
        ?: throw IllegalStateException("无法解码图片")

/** Scale so the longest side equals [maxSide], preserving aspect ratio. The source is NOT recycled. */
private fun scaleToMax(src: Bitmap, maxSide: Int): Bitmap {
    val longest = maxOf(src.width, src.height)
    if (longest <= maxSide) return src
    val ratio = maxSide.toFloat() / longest
    val tw = (src.width * ratio).toInt().coerceAtLeast(1)
    val th = (src.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, tw, th, true)
}

/**
 * Remove a (roughly) solid background by chroma-keying on the average of the
 * four corner pixels. Pixels within [tolerance] (Euclidean RGB distance) of that
 * background color become fully transparent. Works best for images with a
 * near-solid background (white, black, or a single color).
 */
private fun makeTransparent(src: Bitmap, tolerance: Int): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val c0 = px[0]
    val c1 = px[w - 1]
    val c2 = px[(h - 1) * w]
    val c3 = px[(h - 1) * w + w - 1]
    val bgR = (listOf(c0, c1, c2, c3).map { it.ushr(16) and 0xFF }.average()).toInt()
    val bgG = (listOf(c0, c1, c2, c3).map { it.ushr(8) and 0xFF }.average()).toInt()
    val bgB = (listOf(c0, c1, c2, c3).map { it and 0xFF }.average()).toInt()
    val t2 = tolerance * tolerance
    for (i in px.indices) {
        val p = px[i]
        val r = p.ushr(16) and 0xFF
        val g = p.ushr(8) and 0xFF
        val b = p and 0xFF
        val dr = r - bgR
        val dg = g - bgG
        val db = b - bgB
        if (dr * dr + dg * dg + db * db <= t2) {
            px[i] = p and 0x00FFFFFF // clear alpha channel
        }
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    src.recycle()
    return out
}

/** Decode an image URI, downsampling to keep the longest side within [maxSide]. */
private fun decodeDownscaled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(IO.input(context, uri), null, bounds)
    val (ow, oh) = bounds.outWidth to bounds.outHeight
    var sample = 1
    while (maxOf(ow / sample, oh / sample) > maxSide) sample *= 2
    val decoded = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = BitmapFactory.decodeStream(IO.input(context, uri), null, decoded) ?: return null
    if (maxOf(bmp.width, bmp.height) > maxSide) {
        val scaled = scaleToMax(bmp, maxSide)
        bmp.recycle()
        return scaled
    }
    return bmp
}

// =================================================================== converters

/** PDF → 单图 (zip of per-page PNGs) */
private class PdfToImagesConverter : Converter {
    override val type = ConversionType.PDF_TO_IMAGES
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val pages = renderPdfPages(context, inputs[0], 150f)
        ZipOutputStream(IO.output(context, output)).use { zip ->
            pages.forEachIndexed { i, bmp ->
                zip.putNextEntry(ZipEntry("page_%02d.png".format(i + 1)))
                bmp.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                bmp.recycle()
            }
        }
    }
}

/** PDF → 长图 (single tall PNG) */
private class PdfToLongImageConverter : Converter {
    override val type = ConversionType.PDF_TO_LONG_IMAGE
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val pages = renderPdfPages(context, inputs[0], 110f)
        val targetW = 1080
        val scaled = pages.map { scaleToWidth(it, targetW) }
        val totalH = scaled.sumOf { it.height }.coerceAtLeast(1)
        val long = Bitmap.createBitmap(targetW, totalH, Bitmap.Config.ARGB_8888)
        val c = Canvas(long)
        var y = 0
        for (b in scaled) {
            c.drawBitmap(b, 0f, y.toFloat(), null)
            y += b.height
            b.recycle()
        }
        long.compress(Bitmap.CompressFormat.PNG, 95, IO.output(context, output))
        long.recycle()
    }
}

/** PDF → TXT */
private class PdfToTxtConverter : Converter {
    override val type = ConversionType.PDF_TO_TXT
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val text = extractPdfText(context, inputs[0])
        IO.output(context, output).bufferedWriter().use { it.write(text) }
    }
}

/** PDF → Word (.docx) */
private class PdfToWordConverter : Converter {
    override val type = ConversionType.PDF_TO_WORD
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val text = extractPdfText(context, inputs[0])
        IO.output(context, output).write(OfficeKit.docxWrite(text))
    }
}

/** PDF → Excel (.xlsx) */
private class PdfToExcelConverter : Converter {
    override val type = ConversionType.PDF_TO_EXCEL
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val text = extractPdfText(context, inputs[0])
        IO.output(context, output).write(OfficeKit.xlsxWrite(text))
    }
}

/** PDF → PPT (.pptx, one slide per page image) */
private class PdfToPptConverter : Converter {
    override val type = ConversionType.PDF_TO_PPT
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val pages = renderPdfPages(context, inputs[0], 110f)
        val pngs = pages.map { bmp ->
            ByteArrayOutputStream().use { baos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 95, baos)
                bmp.recycle()
                baos.toByteArray()
            }
        }
        IO.output(context, output).write(OfficeKit.pptxWrite(pngs))
    }
}

/** TXT → PDF (image-based pages, CJK-safe) */
private class TxtToPdfConverter : Converter {
    override val type = ConversionType.TXT_TO_PDF
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val text = IO.input(context, inputs[0]).bufferedReader().readText()
        val pages = TextPageRenderer.render(text)
        saveBitmapsAsPdf(pages.map { PdfPage(it.bitmap, it.widthPt, it.heightPt) }, IO.output(context, output))
    }
}

/** 图片 → PDF (each image a page) */
private class ImagesToPdfConverter : Converter {
    override val type = ConversionType.IMAGES_TO_PDF
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val dpi = 150f
        val pages = inputs.map { uri ->
            val bmp = BitmapFactory.decodeStream(IO.input(context, uri))
                ?: throw IllegalStateException("无法解码图片: $uri")
            val wPt = bmp.width / dpi * 72f
            val hPt = bmp.height / dpi * 72f
            PdfPage(bmp, wPt, hPt)
        }
        saveBitmapsAsPdf(pages, IO.output(context, output))
    }
}

/** 长图 → PDF (single page) */
private class LongImageToPdfConverter : Converter {
    override val type = ConversionType.LONG_IMAGE_TO_PDF
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val bmp = BitmapFactory.decodeStream(IO.input(context, inputs[0]))
            ?: throw IllegalStateException("无法解码图片: $inputs")
        val dpi = 150f
        val wPt = bmp.width / dpi * 72f
        val hPt = bmp.height / dpi * 72f
        saveBitmapsAsPdf(listOf(PdfPage(bmp, wPt, hPt)), IO.output(context, output))
    }
}

/** Word → PDF (image-based pages, CJK-safe) */
private class WordToPdfConverter : Converter {
    override val type = ConversionType.WORD_TO_PDF
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val text = OfficeKit.docxRead(context, inputs[0])
        val pages = TextPageRenderer.render(text)
        saveBitmapsAsPdf(pages.map { PdfPage(it.bitmap, it.widthPt, it.heightPt) }, IO.output(context, output))
    }
}

/** Excel → PDF (image-based pages, CJK-safe) */
private class ExcelToPdfConverter : Converter {
    override val type = ConversionType.EXCEL_TO_PDF
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val text = OfficeKit.xlsxRead(context, inputs[0])
        val pages = TextPageRenderer.render(text)
        saveBitmapsAsPdf(pages.map { PdfPage(it.bitmap, it.widthPt, it.heightPt) }, IO.output(context, output))
    }
}

/** PPT → PDF (image-based pages, CJK-safe) */
private class PptToPdfConverter : Converter {
    override val type = ConversionType.PPT_TO_PDF
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val text = OfficeKit.pptxRead(context, inputs[0])
        val pages = TextPageRenderer.render(text)
        saveBitmapsAsPdf(pages.map { PdfPage(it.bitmap, it.widthPt, it.heightPt) }, IO.output(context, output))
    }
}

/** 合并 PDF */
private class MergeConverter : Converter {
    override val type = ConversionType.MERGE
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val merger = com.tom_roush.pdfbox.multipdf.PDFMergerUtility()
        PDDocument().use { out ->
            for (uri in inputs) {
                IO.input(context, uri).use { `is` ->
                    PDDocument.load(`is`).use { src -> merger.appendDocument(out, src) }
                }
            }
            out.save(IO.output(context, output))
        }
    }
}

/** 拆分 PDF (zip of per-page PDFs) */
private class SplitConverter : Converter {
    override val type = ConversionType.SPLIT
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        IO.input(context, inputs[0]).use { `is` ->
            PDDocument.load(`is`).use { doc ->
                val splitter = com.tom_roush.pdfbox.multipdf.Splitter()
                splitter.setSplitAtPage(1)
                val parts = splitter.split(doc)
                ZipOutputStream(IO.output(context, output)).use { zip ->
                    parts.forEachIndexed { i, d ->
                        val baos = ByteArrayOutputStream()
                        d.save(baos)
                        d.close()
                        zip.putNextEntry(ZipEntry("page_%02d.pdf".format(i + 1)))
                        zip.write(baos.toByteArray())
                        zip.closeEntry()
                    }
                }
            }
        }
    }
}

/** 压缩 PDF (re-render pages at lower DPI → smaller image-only PDF) */
private class CompressConverter : Converter {
    override val type = ConversionType.COMPRESS
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val dpi = 100f
        IO.input(context, inputs[0]).use { `is` ->
            PDDocument.load(`is`).use { doc ->
                val renderer = PDFRenderer(doc)
                PDDocument().use { out ->
                    for (i in 0 until doc.numberOfPages) {
                        val bmp = renderer.renderImageWithDPI(i, dpi, ImageType.RGB)
                        val wPt = bmp.width / dpi * 72f
                        val hPt = bmp.height / dpi * 72f
                        val page = PDPage(PDRectangle(wPt, hPt))
                        out.addPage(page)
                        PDPageContentStream(out, page).use { cs ->
                            val img = LosslessFactory.createFromImage(out, bmp)
                            cs.drawImage(img, 0f, 0f, wPt, hPt)
                        }
                        bmp.recycle()
                    }
                    out.save(IO.output(context, output))
                }
            }
        }
    }
}

// =================================================================== registry
/** 图片 → 透明图标 (single .ico embedding multiple transparent PNG sizes) */
private class ImageToIconConverter : Converter {
    override val type = ConversionType.IMAGE_TO_ICON
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val src = BitmapFactory.decodeStream(IO.input(context, inputs[0]))
            ?: throw IllegalStateException("无法解码图片: $inputs")
        val transparent = makeTransparent(src, 110)
        // Standard icon sizes (px). 256 stored as 0 in the ICO header.
        val sizes = listOf(16, 24, 32, 48, 64, 128, 256)
        val pngs = sizes.map { s ->
            val icon = scaleToMax(transparent, s)
            val baos = ByteArrayOutputStream()
            icon.compress(Bitmap.CompressFormat.PNG, 100, baos)
            if (icon != transparent) icon.recycle()
            s to baos.toByteArray()
        }
        val ico = buildIco(pngs)
        transparent.recycle()
        IO.output(context, output).write(ico)
    }
}

/**
 * Build a Windows .ico container (.ico) embedding one or more PNG images.
 * Each entry stores the raw PNG bytes; sizes >= 256 are flagged as 0 in the
 * directory header (the ICO convention for "256 or larger").
 */
private fun buildIco(entries: List<Pair<Int, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    var offset = 6 + 16 * entries.size
    val dir = ByteArray(16 * entries.size)
    entries.forEachIndexed { i, (size, png) ->
        val e = ByteArray(16)
        val w = if (size >= 256) 0 else size
        e[0] = w.toByte()      // width
        e[1] = w.toByte()      // height
        e[2] = 0               // color count (0 = >256 colors)
        e[3] = 0               // reserved
        e[4] = 1; e[5] = 0     // color planes
        e[6] = 32; e[7] = 0    // bits per pixel
        e[8]  = (png.size and 0xFF).toByte()
        e[9]  = ((png.size ushr 8) and 0xFF).toByte()
        e[10] = ((png.size ushr 16) and 0xFF).toByte()
        e[11] = ((png.size ushr 24) and 0xFF).toByte()
        e[12] = (offset and 0xFF).toByte()
        e[13] = ((offset ushr 8) and 0xFF).toByte()
        e[14] = ((offset ushr 16) and 0xFF).toByte()
        e[15] = ((offset ushr 24) and 0xFF).toByte()
        System.arraycopy(e, 0, dir, i * 16, 16)
        offset += png.size
    }
    // ICONDIR header
    out.write(0); out.write(0)                                   // reserved
    out.write(1); out.write(0)                                   // type = icon
    out.write(entries.size and 0xFF); out.write((entries.size ushr 8) and 0xFF) // count
    out.write(dir)
    for ((_, png) in entries) out.write(png)
    return out.toByteArray()
}

/** 图片压缩 (ZIP of JPEGs, level from options) */
private class ImageCompressConverter : Converter {
    override val type = ConversionType.IMAGE_COMPRESS
    private var level = 1 // 0=高画质, 1=标准, 2=强压缩
    override fun setOptions(options: Bundle) {
        level = options.getInt("level", 1).coerceIn(0, 2)
    }
    override fun convert(context: Context, inputs: List<Uri>, output: Uri) {
        val (quality, maxSide) = when (level) {
            0 -> 95 to 4000   // 高画质：几乎不损
            1 -> 75 to 1920   // 标准：均衡
            else -> 50 to 1280 // 强压缩：体积最小
        }
        ZipOutputStream(IO.output(context, output)).use { zip ->
            inputs.forEachIndexed { idx, uri ->
                val bmp = decodeDownscaled(context, uri, maxSide)
                    ?: throw IllegalStateException("无法解码图片: $uri")
                zip.putNextEntry(ZipEntry("compressed_%02d.jpg".format(idx + 1)))
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, zip)
                zip.closeEntry()
                bmp.recycle()
            }
        }
    }
}

object Converters {
    private val all: Map<ConversionType, Converter> = mapOf(
        ConversionType.PDF_TO_IMAGES to PdfToImagesConverter(),
        ConversionType.PDF_TO_LONG_IMAGE to PdfToLongImageConverter(),
        ConversionType.PDF_TO_TXT to PdfToTxtConverter(),
        ConversionType.PDF_TO_WORD to PdfToWordConverter(),
        ConversionType.PDF_TO_EXCEL to PdfToExcelConverter(),
        ConversionType.PDF_TO_PPT to PdfToPptConverter(),
        ConversionType.TXT_TO_PDF to TxtToPdfConverter(),
        ConversionType.IMAGES_TO_PDF to ImagesToPdfConverter(),
        ConversionType.LONG_IMAGE_TO_PDF to LongImageToPdfConverter(),
        ConversionType.WORD_TO_PDF to WordToPdfConverter(),
        ConversionType.EXCEL_TO_PDF to ExcelToPdfConverter(),
        ConversionType.PPT_TO_PDF to PptToPdfConverter(),
        ConversionType.MERGE to MergeConverter(),
        ConversionType.SPLIT to SplitConverter(),
        ConversionType.COMPRESS to CompressConverter(),
        ConversionType.IMAGE_TO_ICON to ImageToIconConverter(),
        ConversionType.IMAGE_COMPRESS to ImageCompressConverter()
    )

    fun get(type: ConversionType): Converter =
        all[type] ?: throw IllegalArgumentException("未找到转换器: $type")
}
