package com.topjohnwu.magisk.core.utils

import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.superuser.Shell

/**
 * Installs/removes the bootloop protector service script.
 *
 * The script (assets/bootloop_protector.sh) lives in /data/adb/service.d and uses marker files in
 * /data/adb/YABP to detect a boot that never completes, then disables all modules.
 */
object BootloopProtector {

    private const val SCRIPT = "bootloop_protector.sh"
    private const val SERVICE_DIR = "/data/adb/service.d"
    private const val MARKER_DIR = "/data/adb/YABP"
    private const val TARGET = "$SERVICE_DIR/$SCRIPT"

    fun isEnabled(): Boolean = Shell.cmd("[ -f $TARGET ]").exec().isSuccess

    fun enable(): Boolean = runCatching {
        val script = AppContext.assets.open(SCRIPT).bufferedReader().use { it.readText() }
        Shell.cmd("mkdir -p $SERVICE_DIR $MARKER_DIR").exec()
        Shell.cmd("cat > $TARGET << 'BOOTLOOP_EOF'\n$script\nBOOTLOOP_EOF").exec()
        Shell.cmd("chmod 755 $TARGET").exec().isSuccess
    }.getOrDefault(false)

    fun disable(): Boolean = runCatching {
        Shell.cmd("rm -f $TARGET", "rm -rf $MARKER_DIR").exec().isSuccess
    }.getOrDefault(false)
}
