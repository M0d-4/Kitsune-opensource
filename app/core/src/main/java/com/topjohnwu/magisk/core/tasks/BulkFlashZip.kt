package com.topjohnwu.magisk.core.tasks

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Installs several module zips one after another, reporting progress and collecting a result
 * per zip. Each zip goes through the regular [FlashZip] path.
 *
 * @param console receives the human readable output (also shown in the UI)
 * @param logs receives the raw installer output for the saved log
 * @param onProgress called before each zip starts: (1-based index, total, zip name)
 */
class BulkFlashZip(
    private val uris: List<Uri>,
    private val console: MutableList<String>,
    private val logs: MutableList<String>,
    private val onProgress: (current: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
) {

    /** Picks the module id out of installer output, if the installer printed one. */
    fun extractModuleIdFromOutput(output: List<String>): String? {
        for (line in output) {
            if ("Module saved for backup:" in line) {
                return line.substringAfter("Module saved for backup:").trim()
            }
            if (line.startsWith("- Installing") && line.endsWith(".zip")) {
                return line.removePrefix("- Installing ").removeSuffix(".zip").trim()
            }
        }
        return null
    }

    suspend fun exec(): List<BulkFlashResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<BulkFlashResult>()
        console.add("=== Bulk Module Installation ===")
        console.add("- ${uris.size} modules to install")
        console.add("")

        uris.forEachIndexed { index, uri ->
            val position = index + 1
            val name = uri.lastPathSegment ?: "module_$position"
            console.add("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            console.add("[$position/${uris.size}] Installing: $name")
            console.add("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            onProgress(position, uris.size, name)

            val output = mutableListOf<String>()
            try {
                val success = FlashZip(uri, output, logs).exec()
                output.forEach { console.add("  $it") }
                results.add(
                    BulkFlashResult(
                        uri = uri,
                        moduleId = extractModuleIdFromOutput(output),
                        success = success,
                        error = if (success) null else "Installation failed",
                    )
                )
                console.add(if (success) "  ✓ Installed successfully" else "  ✗ Installation failed")
            } catch (e: Exception) {
                Timber.e(e, "Failed to install module: $name")
                console.add("  ✗ Error: ${e.message}")
                results.add(BulkFlashResult(uri, null, false, e.message))
            }
            console.add("")
        }
        results
    }
}
