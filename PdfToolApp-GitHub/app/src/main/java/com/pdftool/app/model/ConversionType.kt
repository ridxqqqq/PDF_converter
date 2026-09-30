package com.pdftool.app.model

/**
 * Every feature the app supports. The metadata here drives the home grid,
 * the file pickers (input MIME filter / output MIME) and the converter registry.
 *
 * `tint` is a plain ARGB color used to color the feature card icon.
 */
enum class ConversionType(
    val title: String,
    val desc: String,
    val category: Category,
    val inputMimeTypes: Array<String>,
    val outputMimeType: String,
    val outputExtension: String,
    val multipleInput: Boolean,
    val tint: Int
) {
    // ---------- PDF → others ----------
    PDF_TO_IMAGES(
        "PDF → 单图", "每页导出为一张 PNG 图片（打包成 ZIP）", Category.PDF_EXPORT,
        arrayOf("application/pdf"), "application/zip", "zip", false, 0xFF1565C0.toInt()
    ),
    PDF_TO_LONG_IMAGE(
        "PDF → 长图", "把所有页面拼成一张长图 PNG", Category.PDF_EXPORT,
        arrayOf("application/pdf"), "image/png", "png", false, 0xFF1E88E5.toInt()
    ),
    PDF_TO_TXT(
        "PDF → TXT", "提取 PDF 中的文字内容", Category.PDF_EXPORT,
        arrayOf("application/pdf"), "text/plain", "txt", false, 0xFF2196F3.toInt()
    ),
    PDF_TO_WORD(
        "PDF → Word", "把 PDF 文字转为 .docx 文档", Category.PDF_EXPORT,
        arrayOf("application/pdf"),
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "docx", false, 0xFF0D8AEE.toInt()
    ),
    PDF_TO_EXCEL(
        "PDF → Excel", "把 PDF 文字按行转为 .xlsx 表格", Category.PDF_EXPORT,
        arrayOf("application/pdf"),
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xlsx", false, 0xFF039BE5.toInt()
    ),
    PDF_TO_PPT(
        "PDF → PPT", "每页作为一张幻灯片导出 .pptx", Category.PDF_EXPORT,
        arrayOf("application/pdf"),
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "pptx", false, 0xFF00ACC1.toInt()
    ),

    // ---------- others → PDF ----------
    TXT_TO_PDF(
        "TXT → PDF", "把文本文件排版后生成 PDF", Category.PDF_IMPORT,
        arrayOf("text/plain"), "application/pdf", "pdf", false, 0xFF2E7D32.toInt()
    ),
    IMAGES_TO_PDF(
        "图片 → PDF", "一张或多张图片各占一页生成 PDF", Category.PDF_IMPORT,
        arrayOf("image/*"), "application/pdf", "pdf", true, 0xFF388E3C.toInt()
    ),
    LONG_IMAGE_TO_PDF(
        "长图 → PDF", "把一张长图作为一页生成 PDF", Category.PDF_IMPORT,
        arrayOf("image/*"), "application/pdf", "pdf", false, 0xFF43A047.toInt()
    ),
    WORD_TO_PDF(
        "Word → PDF", "把 .docx 文字内容转为 PDF", Category.PDF_IMPORT,
        arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        "application/pdf", "pdf", false, 0xFF1B5E20.toInt()
    ),
    EXCEL_TO_PDF(
        "Excel → PDF", "把 .xlsx 表格内容转为 PDF", Category.PDF_IMPORT,
        arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        "application/pdf", "pdf", false, 0xFF2E7D32.toInt()
    ),
    PPT_TO_PDF(
        "PPT → PDF", "把 .pptx 文字内容转为 PDF", Category.PDF_IMPORT,
        arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        "application/pdf", "pdf", false, 0xFF00897B.toInt()
    ),

    // ---------- Tools ----------
    MERGE(
        "合并 PDF", "把多个 PDF 按顺序合并成一个", Category.TOOL,
        arrayOf("application/pdf"), "application/pdf", "pdf", true, 0xFFED6C02.toInt()
    ),
    SPLIT(
        "拆分 PDF", "把每一页拆成独立的 PDF（打包 ZIP）", Category.TOOL,
        arrayOf("application/pdf"), "application/zip", "zip", false, 0xFFF57C00.toInt()
    ),
    COMPRESS(
        "压缩 PDF", "降低分辨率以显著减小文件体积", Category.TOOL,
        arrayOf("application/pdf"), "application/pdf", "pdf", false, 0xFFEF6C00.toInt()
    ),

    // ---------- Image tools ----------
    IMAGE_TO_ICON(
        "图片 → 透明图标", "自动抠除四角背景色，导出多尺寸透明 .ico 图标", Category.IMAGE,
        arrayOf("image/*"), "image/x-icon", "ico", false, 0xFF5E35B1.toInt()
    ),
    IMAGE_COMPRESS(
        "图片压缩", "按压缩等级压缩（支持批量），输出 JPEG（ZIP）", Category.IMAGE,
        arrayOf("image/*"), "application/zip", "zip", true, 0xFF7E57C2.toInt()
    );

    enum class Category(val label: String) {
        PDF_EXPORT("PDF 转其他"),
        PDF_IMPORT("其他转 PDF"),
        TOOL("PDF 工具"),
        IMAGE("图片工具")
    }
}
