package com.pdftool.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.pdftool.app.R
import com.pdftool.app.engine.Converters
import com.pdftool.app.model.ConversionType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversionActivity : Activity() {

    private lateinit var convType: ConversionType
    private var inputs: List<Uri> = emptyList()
    private var outputUri: Uri? = null

    private lateinit var selectedFiles: TextView
    private lateinit var status: TextView
    private lateinit var progress: View
    private lateinit var btnStart: Button
    private lateinit var btnShare: Button
    private lateinit var btnHome: Button
    private lateinit var optionsPanel: View
    private lateinit var levelGroup: RadioGroup

    companion object {
        private const val REQ_INPUT = 1
        private const val SHARE_AUTHORITY = "com.pdftool.app.share"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversion)

        val name = intent.getStringExtra("type")
        if (name == null) {
            finish()
            return
        }
        convType = ConversionType.valueOf(name)

        findViewById<TextView>(R.id.title).text = convType.title
        findViewById<TextView>(R.id.desc).text = convType.desc
        selectedFiles = findViewById(R.id.selectedFiles)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        btnStart = findViewById(R.id.btnStart)
        btnShare = findViewById(R.id.btnShare)
        btnHome = findViewById(R.id.btnHome)
        optionsPanel = findViewById(R.id.optionsPanel)
        levelGroup = findViewById(R.id.levelGroup)
        // Only the image-compress feature exposes a level selector.
        optionsPanel.visibility =
            if (convType == ConversionType.IMAGE_COMPRESS) View.VISIBLE else View.GONE

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnSelect).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_MIME_TYPES, convType.inputMimeTypes)
                if (convType.multipleInput) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                setType(convType.inputMimeTypes.first())
            }
            startActivityForResult(intent, REQ_INPUT)
        }

        btnStart.setOnClickListener {
            if (inputs.isEmpty()) {
                Toast.makeText(this, R.string.toast_select_first, Toast.LENGTH_SHORT).show()
            } else {
                runConversion()
            }
        }

        btnShare.setOnClickListener { shareOutput() }
        btnHome.setOnClickListener { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return
        if (requestCode != REQ_INPUT) return
        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else if (data.data != null) {
            uris.add(data.data!!)
        }
        if (uris.isNotEmpty()) {
            inputs = uris
            selectedFiles.text = getString(R.string.selected_files, inputs.size)
            btnStart.isEnabled = true
            status.setText(R.string.status_ready)
        } else {
            Toast.makeText(this, R.string.toast_select_first, Toast.LENGTH_SHORT).show()
        }
    }

    private fun runConversion() {
        progress.visibility = View.VISIBLE
        status.setText(R.string.status_running)
        btnStart.isEnabled = false
        val options = android.os.Bundle().apply {
            if (convType == ConversionType.IMAGE_COMPRESS) {
                val lvl = when (levelGroup.checkedRadioButtonId) {
                    R.id.lvlHigh -> 0
                    R.id.lvlStrong -> 2
                    else -> 1
                }
                putInt("level", lvl)
            }
        }
        Thread {
            var target: Uri? = null
            try {
                val t = createOutputUri()
                target = t
                val converter = Converters.get(convType)
                converter.setOptions(options)
                converter.convert(this, inputs, t)
                finalizeOutput(t)
                runOnUiThread { onSuccess(t) }
            } catch (e: Exception) {
                e.printStackTrace()
                target?.let { discardOutput(it) }
                val msg = e.message ?: "未知错误"
                runOnUiThread { onError(msg) }
            }
        }.start()
    }

    /**
     * Create the output document ourselves instead of asking the OEM file picker
     * (ACTION_CREATE_DOCUMENT), which on many devices forces a wrong extension and
     * MIME for uncommon types such as `image/x-icon` (the reported bug: an .ico
     * conversion saved as .jpg). With this path the result is ALWAYS created as
     * `<name>.ico` and the conversion bytes are written into it:
     *  - Android 10+ (API 29): a MediaStore entry in Pictures/PdfTool with
     *    DISPLAY_NAME and MIME_TYPE set explicitly (IS_PENDING while writing);
     *  - Android 6.0–9 (API 23–28): a file inside the app-specific external
     *    `output/` directory served by [com.pdftool.app.engine.ShareProvider]
     *    (no storage permission needed), shared out as a content:// URI.
     */
    private fun createOutputUri(): Uri {
        val base = outputBaseName()
        return if (Build.VERSION.SDK_INT >= 29) {
            // Preferred: Pictures/PdfTool. Some OEM Android 10 media providers are
            // strict about unusual MIME types in the images collection, so fall
            // back to the Downloads collection (accepts arbitrary types).
            try {
                insertMediaStore(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    Environment.DIRECTORY_PICTURES + "/PdfTool", base
                )
            } catch (e: Exception) {
                insertMediaStore(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    Environment.DIRECTORY_DOWNLOADS + "/PdfTool", base
                )
            }
        } else {
            Uri.parse("content://$SHARE_AUTHORITY/output/$base.ico")
        }
    }

    private fun insertMediaStore(collection: Uri, relativePath: String, base: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$base.ico")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-icon")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return contentResolver.insert(collection, values)
            ?: throw IllegalStateException("无法创建输出文件")
    }

    /** Base file name from the selected input ("photo.jpg" → photo_MMDD_HHmmss). */
    private fun outputBaseName(): String {
        val ts = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
        val stem = try {
            queryName(inputs[0]).substringBeforeLast('.')
        } catch (_: Exception) {
            ""
        }.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)
        return if (stem.isEmpty()) "icon_$ts" else "${stem}_$ts"
    }

    /** Publish a MediaStore row once the bytes are fully written. */
    private fun finalizeOutput(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 29 && uri.authority == "media") {
            try {
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(uri, cv, null, null)
            } catch (_: Exception) {
            }
        }
    }

    /** Remove the half-written output if the conversion failed. */
    private fun discardOutput(uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29 && uri.authority == "media") {
                contentResolver.delete(uri, null, null)
            } else if (uri.authority == SHARE_AUTHORITY) {
                val name = uri.lastPathSegment
                val dir = getExternalFilesDir("output")
                if (name != null && dir != null) java.io.File(dir, name).delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun onSuccess(uri: Uri) {
        progress.visibility = View.GONE
        status.setText(R.string.status_done)
        btnShare.visibility = View.VISIBLE
        btnHome.visibility = View.VISIBLE
        Toast.makeText(
            this,
            getString(R.string.toast_saved, queryName(uri)),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun onError(msg: String) {
        progress.visibility = View.GONE
        btnStart.isEnabled = true
        status.text = getString(R.string.status_error, msg)
    }

    private fun queryName(uri: Uri): String {
        var name = "文件"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx)
            }
        }
        return name
    }

    private fun shareOutput() {
        val uri = outputUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            setType(convType.outputMimeType)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享文件"))
    }
}
