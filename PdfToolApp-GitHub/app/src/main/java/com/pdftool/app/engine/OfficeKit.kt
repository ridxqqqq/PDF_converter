package com.pdftool.app.engine

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pure-Kotlin reader/writer for .docx / .xlsx / .pptx using the raw OOXML
 * (zip + XML) format.
 *
 * Why not Apache POI? POI references java.awt, which does NOT exist on Android
 * and crashes at runtime. Generating minimal valid OOXML by hand keeps the app
 * dependency-light and 100% compatible with every Android version.
 *
 * Scope: text-level round trips. Complex formatting (styles, merged cells,
 * shapes, charts) is intentionally flattened.
 */
object OfficeKit {

    private const val NS_DOC = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val NS_XLS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_PPT = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"

    // ----------------------------------------------------------------- helpers
    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun zip(parts: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in parts) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun readEntry(context: Context, uri: Uri, endsWith: String): String {
        IO.input(context, uri).use { `in` ->
            ZipInputStream(`in`).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(endsWith)) {
                        return zis.bufferedReader().readText()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return ""
    }

    // =================================================================== DOCX
    fun docxRead(context: Context, uri: Uri): String {
        val xml = readEntry(context, uri, "word/document.xml")
        val sb = StringBuilder()
        // Capture either a text run (<w:t>) or a paragraph end (</w:p>) and
        // reconstruct order, so paragraph breaks are preserved.
        val re = Pattern.compile("(?:<w:t[^>]*>(.*?)</w:t>)|(?:</w:p>)", Pattern.DOTALL)
        val m = re.matcher(xml)
        while (m.find()) {
            if (m.group(1) != null) sb.append(m.group(1)) else sb.append('\n')
        }
        return sb.toString()
    }

    fun docxWrite(text: String): ByteArray {
        val body = text.split("\n").joinToString("") { p ->
            "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(p)}</w:t></w:r></w:p>"
        }
        val documentXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"$NS_DOC\"><w:body>$body</w:body></w:document>"
        val ct =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"$NS_CT\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "</Types>"
        val rels =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"$NS_PKG_REL\">" +
            "<Relationship Id=\"rId1\" Type=\"$NS_R/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"
        return zip(
            mapOf(
                "[Content_Types].xml" to ct.toByteArray(),
                "_rels/.rels" to rels.toByteArray(),
                "word/document.xml" to documentXml.toByteArray()
            )
        )
    }

    // =================================================================== XLSX
    private fun colToIndex(letters: String): Int {
        var n = 0
        for (ch in letters) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    private fun colRef(idx: Int): String {
        var n = idx
        var s = ""
        while (n >= 0) {
            s = ('A' + n % 26) + s
            n = n / 26 - 1
        }
        return s
    }

    fun xlsxRead(context: Context, uri: Uri): String {
        val shared = readSharedStrings(context, uri)
        val sb = StringBuilder()
        IO.input(context, uri).use { `in` ->
            ZipInputStream(`in`).use { zis ->
                val sheets = mutableListOf<String>()
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml")) {
                        sheets.add(zis.bufferedReader().readText())
                    }
                    entry = zis.nextEntry
                }
                sheets.sortedBy { Regex("sheet(\\d+)\\.xml").find(it)?.groupValues?.get(1)?.toInt() ?: 0 }
                    .forEach { xml -> sb.append(parseSheet(xml, shared)).append("\n") }
            }
        }
        return sb.toString()
    }

    private fun readSharedStrings(context: Context, uri: Uri): List<String> {
        val xml = readEntry(context, uri, "xl/sharedStrings.xml")
        if (xml.isEmpty()) return emptyList()
        val re = Pattern.compile("<si>.*?<t[^>]*>(.*?)</t>.*?</si>", Pattern.DOTALL)
        val m = re.matcher(xml)
        val list = mutableListOf<String>()
        while (m.find()) list.add(m.group(1))
        return list
    }

    private fun parseSheet(xml: String, shared: List<String>): String {
        val rowRe = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL)
        val cellRe = Pattern.compile("<c[^>]*?(?:/>|>.*?</c>)", Pattern.DOTALL)
        val out = StringBuilder()
        val rm = rowRe.matcher(xml)
        while (rm.find()) {
            val rowXml = rm.group(1)
            val cells = mutableMapOf<Int, String>()
            val cm = cellRe.matcher(rowXml)
            while (cm.find()) {
                val cellXml = cm.group()
                if (cellXml.endsWith("/>")) continue
                val rAttr = Regex("r=\"([A-Z]+)\\d+\"").find(cellXml)?.groupValues?.get(1) ?: "A"
                val tAttr = Regex("t=\"(\\w+)\"").find(cellXml)?.groupValues?.get(1)
                val inline = cellXml.contains("<is>")
                val valueText = if (inline) {
                    Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).find(cellXml)?.groupValues?.get(1) ?: ""
                } else {
                    Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(cellXml)?.groupValues?.get(1) ?: ""
                }
                val finalVal = if (tAttr == "s") {
                    shared.getOrNull(valueText.toIntOrNull() ?: -1) ?: ""
                } else valueText
                cells[colToIndex(rAttr)] = finalVal
            }
            if (cells.isNotEmpty()) {
                val max = cells.keys.maxOrNull() ?: 0
                val row = (0..max).joinToString("\t") { cells[it] ?: "" }
                out.append(row).append("\n")
            }
        }
        return out.toString()
    }

    fun xlsxWrite(text: String): ByteArray {
        val lines = text.split("\n").filter { it.isNotBlank() }
        val rowsXml = lines.mapIndexed { rIdx, line ->
            val cells = line.split(Regex("\t| {2,}"))
            val cellsXml = cells.mapIndexed { cIdx, v ->
                val ref = colRef(cIdx) + (rIdx + 1)
                "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${escapeXml(v)}</t></is></c>"
            }.joinToString("")
            "<row r=\"${rIdx + 1}\">$cellsXml</row>"
        }.joinToString("")
        val sheetXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"$NS_XLS\"><sheetData>$rowsXml</sheetData></worksheet>"
        val workbookXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"$NS_XLS\" xmlns:r=\"$NS_R\">" +
            "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        val wbRels =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"$NS_PKG_REL\">" +
            "<Relationship Id=\"rId1\" Type=\"$NS_R/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "</Relationships>"
        val ct =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"$NS_CT\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "</Types>"
        val rels =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"$NS_PKG_REL\">" +
            "<Relationship Id=\"rId1\" Type=\"$NS_R/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"
        return zip(
            mapOf(
                "[Content_Types].xml" to ct.toByteArray(),
                "_rels/.rels" to rels.toByteArray(),
                "xl/workbook.xml" to workbookXml.toByteArray(),
                "xl/_rels/workbook.xml.rels" to wbRels.toByteArray(),
                "xl/worksheets/sheet1.xml" to sheetXml.toByteArray()
            )
        )
    }

    // =================================================================== PPTX
    fun pptxRead(context: Context, uri: Uri): String {
        val sb = StringBuilder()
        IO.input(context, uri).use { `in` ->
            ZipInputStream(`in`).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                        val xml = zis.bufferedReader().readText()
                        val re = Pattern.compile("<a:t[^>]*>(.*?)</a:t>", Pattern.DOTALL)
                        val m = re.matcher(xml)
                        while (m.find()) sb.append(m.group(1)).append(' ')
                        sb.append("\n")
                    }
                    entry = zis.nextEntry
                }
            }
        }
        return sb.toString()
    }

    /**
     * Build a .pptx where each PNG image becomes a full-bleed slide.
     * `images` are raw PNG byte arrays.
     */
    fun pptxWrite(images: List<ByteArray>): ByteArray {
        val parts = mutableMapOf<String, ByteArray>()

        val slideEntries = StringBuilder()
        images.forEachIndexed { i, _ ->
            slideEntries.append("<p:sldId id=\"${256 + i}\" r:id=\"rIdSld${i + 1}\"/>")
        }

        val presXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<p:presentation xmlns:p=\"$NS_PPT\" xmlns:r=\"$NS_R\">" +
            "<p:sldMasterIdLst><p:sldMasterId r:id=\"rIdMaster\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>$slideEntries</p:sldIdLst>" +
            "<p:sldSz cx=\"9144000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
            "</p:presentation>"

        val masterXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<p:sldMaster xmlns:p=\"$NS_PPT\" xmlns:r=\"$NS_R\" xmlns:a=\"$NS_A\">" +
            "<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></p:bgPr></p:bg>" +
            "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree>" +
            "</p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rIdLayout\"/></p:sldLayoutIdLst>" +
            "</p:sldMaster>"

        val layoutXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<p:sldLayout xmlns:p=\"$NS_PPT\" xmlns:r=\"$NS_R\" xmlns:a=\"$NS_A\" type=\"blank\" preserve=\"1\">" +
            "<p:cSld name=\"Blank\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>" +
            "<p:clrMapOwnsrMap/>" +
            "</p:sldLayout>"

        // presentation relationships
        val presRels = StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"$NS_PKG_REL\">" +
            "<Relationship Id=\"rIdMaster\" Type=\"$NS_R/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
            "<Relationship Id=\"rIdLayout\" Type=\"$NS_R/slideLayout\" Target=\"slideLayouts/slideLayout1.xml\"/>"
        )
        images.forEachIndexed { i, _ ->
            presRels.append("<Relationship Id=\"rIdSld${i + 1}\" Type=\"$NS_R/slide\" Target=\"slides/slide${i + 1}.xml\"/>")
        }
        presRels.append("</Relationships>")

        val masterRels =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"$NS_PKG_REL\">" +
            "<Relationship Id=\"rIdLayout\" Type=\"$NS_R/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "</Relationships>"

        val layoutRels =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"$NS_PKG_REL\">" +
            "<Relationship Id=\"rIdMaster\" Type=\"$NS_R/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
            "</Relationships>"

        // content types
        val ct = StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"$NS_CT\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>"
        )
        images.forEachIndexed { i, _ ->
            ct.append("<Override PartName=\"/ppt/slides/slide${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
            ct.append("<Override PartName=\"/ppt/media/image${i + 1}.png\" ContentType=\"image/png\"/>")
        }
        ct.append("</Types>")

        val rootRels =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"$NS_PKG_REL\">" +
            "<Relationship Id=\"rId1\" Type=\"$NS_R/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
            "</Relationships>"

        parts["[Content_Types].xml"] = ct.toString().toByteArray()
        parts["_rels/.rels"] = rootRels.toByteArray()
        parts["ppt/presentation.xml"] = presXml.toByteArray()
        parts["ppt/_rels/presentation.xml.rels"] = presRels.toString().toByteArray()
        parts["ppt/slideMasters/slideMaster1.xml"] = masterXml.toByteArray()
        parts["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = masterRels.toByteArray()
        parts["ppt/slideLayouts/slideLayout1.xml"] = layoutXml.toByteArray()
        parts["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = layoutRels.toByteArray()

        images.forEachIndexed { i, bytes ->
            val slideXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<p:sld xmlns:p=\"$NS_PPT\" xmlns:r=\"$NS_R\" xmlns:a=\"$NS_A\">" +
                "<p:cSld><p:spTree>" +
                "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
                "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
                "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Picture\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>" +
                "<p:blipFill><a:blip r:embed=\"rIdImg\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
                "<p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"9144000\" cy=\"6858000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>" +
                "</p:pic>" +
                "</p:spTree></p:cSld></p:sld>"
            val slideRels =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"$NS_PKG_REL\">" +
                "<Relationship Id=\"rIdLayout\" Type=\"$NS_R/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "<Relationship Id=\"rIdImg\" Type=\"$NS_R/image\" Target=\"../media/image${i + 1}.png\"/>" +
                "</Relationships>"
            parts["ppt/slides/slide${i + 1}.xml"] = slideXml.toByteArray()
            parts["ppt/slides/_rels/slide${i + 1}.xml.rels"] = slideRels.toByteArray()
            parts["ppt/media/image${i + 1}.png"] = bytes
        }

        return zip(parts)
    }
}
