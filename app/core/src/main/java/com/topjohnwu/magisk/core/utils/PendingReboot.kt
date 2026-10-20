package com.topjohnwu.magisk.core.utils

import com.topjohnwu.magisk.core.Config
import java.io.File

/**
 * Tracks "a reboot is needed to apply a change" across app restarts.
 *
 * The flag stores the boot id of the boot in which the change was made, so it clears itself as
 * soon as the device has rebooted (the boot id changes), without the app having to be running.
 */
object PendingReboot {
    private fun bootId() = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    fun mark() {
        Config.pendingRebootBoot = bootId()
    }

    fun isPending(): Boolean {
        val stored = Config.pendingRebootBoot
        return stored.isNotEmpty() && stored == bootId()
    }

    fun clear() {
        Config.pendingRebootBoot = ""
    }
}
