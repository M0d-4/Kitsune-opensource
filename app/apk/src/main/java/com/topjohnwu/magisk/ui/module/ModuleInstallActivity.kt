package com.topjohnwu.magisk.ui.module

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.ui.MainActivity
import com.topjohnwu.magisk.ui.flash.FlashUtils

/**
 * Entry point for "Open with" on a module zip (file:// or content:// with a zip MIME type).
 * It forwards the zip to the app's flash screen and finishes immediately.
 */
class ModuleInstallActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri != null) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = FlashUtils.INTENT_FLASH
                    putExtra(FlashUtils.EXTRA_FLASH_ACTION, Const.Value.FLASH_ZIP)
                    putExtra(FlashUtils.EXTRA_FLASH_URI, uri.toString())
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    // Carry the temporary read permission for content:// URIs along.
                    clipData = ClipData.newRawUri(null, uri)
                }
            )
        }
        finish()
    }
}
