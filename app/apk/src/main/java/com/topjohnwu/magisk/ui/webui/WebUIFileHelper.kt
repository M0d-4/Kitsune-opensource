package com.topjohnwu.magisk.ui.webui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.io.OutputStream

/** Helpers for saving files that a module's web page asked to download. */
object WebUIFileHelper {

    private val illegalChars = Regex("[/\\\\:*?\"<>|]")

    fun sanitizeFilename(filename: String): String =
        illegalChars.replace(filename, "_").take(255)

    /** Extracts `filename=...` from a Content-Disposition header, or returns "download". */
    fun getFilename(contentDisposition: String): String {
        if (contentDisposition.isEmpty()) return "download"
        contentDisposition.split(";").forEach { part ->
            val trimmed = part.trim()
            if (trimmed.startsWith("filename=")) {
                return sanitizeFilename(trimmed.substringAfter('=').trim('"'))
            }
        }
        return "download"
    }

    fun saveContent(context: Context, content: String, filename: String) =
        save(context, filename) { it.write(content.toByteArray(Charsets.UTF_8)) }

    fun saveContent(context: Context, content: ByteArray, filename: String) =
        save(context, filename) { it.write(content) }

    private fun save(context: Context, filename: String, write: (OutputStream) -> Unit) {
        try {
            val savedName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Cannot create file")
                resolver.openOutputStream(uri)!!.use(write)
                filename
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = uniqueFile(dir, filename)
                file.outputStream().use(write)
                file.name
            }
            Toast.makeText(context, "Downloaded to Downloads/$savedName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uniqueFile(dir: File, filename: String): File {
        var file = File(dir, filename)
        val base = filename.substringBeforeLast('.')
        val ext = if ('.' in filename) "." + filename.substringAfterLast('.') else ""
        var n = 1
        while (file.exists()) {
            n++
            file = File(dir, "$base ($n)$ext")
        }
        return file
    }
}
