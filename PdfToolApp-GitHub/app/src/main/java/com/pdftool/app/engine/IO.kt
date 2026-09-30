package com.pdftool.app.engine

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

/** Tiny helper for opening SAF streams. */
object IO {
    fun input(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法打开输入文件: $uri")

    fun output(context: Context, uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("无法写入输出文件: $uri")
}
