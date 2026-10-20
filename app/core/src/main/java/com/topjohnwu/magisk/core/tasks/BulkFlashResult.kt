package com.topjohnwu.magisk.core.tasks

import android.net.Uri

/** Outcome of installing one zip as part of a [BulkFlashZip] run. */
data class BulkFlashResult(
    val uri: Uri,
    val moduleId: String?,
    val success: Boolean,
    val error: String? = null,
)
