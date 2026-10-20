package com.topjohnwu.magisk.ui.webui

import com.topjohnwu.superuser.Shell

/** True if the installed module [id] ships a `webroot/index.html` (or `index.htm`). */
fun moduleHasWebUI(id: String): Boolean {
    val webroot = "/data/adb/modules/$id/webroot"
    return Shell.cmd("test -f '$webroot/index.html' || test -f '$webroot/index.htm'").exec().isSuccess
}
