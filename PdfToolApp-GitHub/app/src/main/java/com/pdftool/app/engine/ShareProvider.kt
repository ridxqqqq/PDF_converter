package com.pdftool.app.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal framework-only content provider (no androidx) that exposes files from
 * the app-specific external `output/` directory to share targets (ACTION_SEND)
 * without any storage permission and without androidx FileProvider.
 *
 * Only plain file names directly inside `getExternalFilesDir("output")` are
 * reachable; path separators and traversal attempts are rejected.
 */
class ShareProvider : ContentProvider() {

    private fun safeName(uri: Uri): String {
        val name = uri.lastPathSegment ?: throw FileNotFoundException("empty uri")
        require(
            name.isNotEmpty() &&
                !name.contains('/') && !name.contains('\\') &&
                name != ".." && !name.contains("..")
        ) { "illegal name: $name" }
        return name
    }

    private fun outputDir(): File {
        val ctx = context ?: throw FileNotFoundException("no context")
        return ctx.getExternalFilesDir("output") ?: throw FileNotFoundException("no output dir")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val dir = outputDir()
        val name = safeName(uri)
        return if (mode == "r") {
            val f = File(dir, name)
            if (!f.isFile) throw FileNotFoundException(name)
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            // Write mode ("w"): create/truncate inside our own output directory.
            dir.mkdirs()
            ParcelFileDescriptor.open(
                File(dir, name),
                ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            )
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val f = try {
            val dir = outputDir()
            File(dir, safeName(uri))
        } catch (_: Exception) {
            return null
        }
        if (!f.isFile) return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val mc = MatrixCursor(cols)
        mc.addRow(Array(cols.size) { i ->
            when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        })
        return mc
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.lowercase()?.endsWith(".ico") == true) "image/x-icon"
        else "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun onCreate(): Boolean = true
}
