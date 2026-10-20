package com.topjohnwu.magisk.ui.webui

import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Serves a module's `webroot` directory through the root file system service, so module pages can
 * be loaded from `/data/adb/modules` even though the app itself cannot read that directory.
 *
 * Requests for `internal/insets.css` return the current window insets as CSS variables.
 */
class RemoteFsPathHandler(
    directory: File,
    private val fs: FileSystemManager,
    private val insetsSupplier: () -> Insets,
    private val onInsetsRequested: (Boolean) -> Unit,
) : WebViewAssetLoader.PathHandler {

    private val directory = File(canonicalDirPath(directory))

    init {
        require(FORBIDDEN_DATA_DIRS.none { canonicalDirPath(this.directory).startsWith(it) }) {
            "The given directory \"$directory\" doesn't exist under an allowed app internal storage directory"
        }
    }

    override fun handle(path: String): WebResourceResponse? {
        if (path == "internal/insets.css") {
            onInsetsRequested(true)
            val css = insetsSupplier().css.toByteArray(Charsets.UTF_8)
            return WebResourceResponse("text/css", "utf-8", ByteArrayInputStream(css))
        }
        return try {
            val file = canonicalFileIfChild(directory, path)
            if (file != null) {
                WebResourceResponse(guessMimeType(path), null, openFile(file))
            } else {
                Log.e(TAG, "The requested file: $path is outside the mounted directory: $directory")
                WebResourceResponse(null, null, null)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error opening the requested path: $path", e)
            WebResourceResponse(null, null, null)
        }
    }

    private fun openFile(file: File): InputStream {
        val stream = fs.getFile(file.absolutePath).newInputStream()
        return if (file.path.endsWith(".svgz")) GZIPInputStream(stream) else stream
    }

    private fun guessMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return MIME_OVERRIDES[ext]
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: DEFAULT_MIME_TYPE
    }

    private companion object {
        const val TAG = "RemoteFsPathHandler"
        const val DEFAULT_MIME_TYPE = "text/plain"
        val FORBIDDEN_DATA_DIRS = arrayOf("/data/data", "/data/system")

        // Types WebView needs to get right for module pages (ES modules, wasm, fonts, svg).
        val MIME_OVERRIDES = mapOf(
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "css" to "text/css",
            "html" to "text/html",
            "htm" to "text/html",
            "json" to "application/json",
            "wasm" to "application/wasm",
            "svg" to "image/svg+xml",
            "svgz" to "image/svg+xml",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "webp" to "image/webp",
            "avif" to "image/avif",
        )

        fun canonicalDirPath(file: File): String {
            val path = file.canonicalPath
            return if (path.endsWith("/")) path else "$path/"
        }

        fun canonicalFileIfChild(parent: File, child: String): File? {
            val parentPath = canonicalDirPath(parent)
            val childPath = File(parent, child).canonicalPath
            return if (childPath.startsWith(parentPath)) File(childPath) else null
        }
    }
}
