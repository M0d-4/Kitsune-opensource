package icu.nullptr.hidemyapplist.common

import android.os.SystemProperties

/**
 * Identity of the Magisk app (the "manager") that is allowed to talk to the app-list hiding
 * service.
 *
 * The manager's package name can change (the Magisk app can hide itself under a random name),
 * and its signing certificate differs between builds, so neither can be compiled in. The Magisk
 * app publishes them through [PROP] right before it enables the feature, as
 * `<first 32 hex chars of the SHA-256 of the signing certificate>:<package name>`.
 */
object ManagerInfo {
    const val PROP = "persist.magisk.applist.manager"

    @Volatile
    var packageName: String = ""
        private set

    @Volatile
    var certHash: String = ""
        private set

    val providerAuthority get() = "$packageName.applist.ServiceProvider"

    val isKnown get() = packageName.isNotEmpty() && certHash.isNotEmpty()

    /** (Re)read the property. Returns true when a manager identity is available. */
    fun reload(): Boolean {
        val value = runCatching { SystemProperties.get(PROP, "") }.getOrDefault("")
        val idx = value.indexOf(':')
        if (idx != 32 || idx == value.length - 1) {
            packageName = ""
            certHash = ""
            return false
        }
        certHash = value.substring(0, idx)
        packageName = value.substring(idx + 1)
        return true
    }
}
