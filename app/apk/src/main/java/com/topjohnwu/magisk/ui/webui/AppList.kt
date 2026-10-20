package com.topjohnwu.magisk.ui.webui

import android.content.Context
import android.content.pm.PackageInfo

data class AppInfo(val packageInfo: PackageInfo, val label: String) {
    val packageName: String get() = packageInfo.packageName
}

/** Cached list of installed packages, used by the WebUI `ksu.listPackages` / `getPackagesInfo` API. */
object AppList {

    @Volatile
    private var cachedApps: List<AppInfo>? = null

    /** Whatever has been loaded so far (empty before [getApps] ran). */
    val applist: List<AppInfo> get() = cachedApps.orEmpty()

    fun getApps(context: Context): List<AppInfo> {
        cachedApps?.let { return it }
        return try {
            val pm = context.packageManager
            pm.getInstalledPackages(0).mapNotNull { info ->
                info.applicationInfo?.let { AppInfo(info, pm.getApplicationLabel(it).toString()) }
            }.also { cachedApps = it }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
