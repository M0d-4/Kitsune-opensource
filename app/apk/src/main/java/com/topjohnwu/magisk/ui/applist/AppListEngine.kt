package com.topjohnwu.magisk.ui.applist

import android.content.Context
import android.content.pm.PackageManager
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.superuser.Shell
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

enum class AppListState {
    DISABLED, ZYGISK_OFF, NOT_STAGED, WAITING, LOADING, ACTIVE, CONFLICT, CRASHED
}

/**
 * Stages the app-list hiding payload (built from app/applist) and tells the service which app is
 * its manager. The Magisk daemon loads the staged payload as a built-in Zygisk module when the
 * `applist_hide` setting is on.
 */
object AppListEngine {

    private const val PAYLOAD_ASSET = "applist_payload.zip"
    private const val PAYLOAD_DIR = "/data/adb/applist/payload"
    private const val IDENTITY_PROP = "persist.magisk.applist.manager"

    val hasBundledPayload: Boolean
        get() = runCatching {
            com.topjohnwu.magisk.core.AppContext.assets.open(PAYLOAD_ASSET).close(); true
        }.getOrDefault(false)

    private fun versionStamp(context: Context) = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
    }.getOrDefault("0")

    fun isStaged(context: Context): Boolean {
        if (Shell.isAppGrantedRoot() != true) return false
        val ver = Shell.cmd("cat $PAYLOAD_DIR/.ver 2>/dev/null").exec().out.joinToString("").trim()
        val libs = Shell.cmd(
            "ls $PAYLOAD_DIR/zygisk/arm64-v8a.so $PAYLOAD_DIR/zygisk/x86_64.so " +
                "$PAYLOAD_DIR/zygisk/armeabi-v7a.so $PAYLOAD_DIR/zygisk/x86.so 2>/dev/null"
        ).exec().out.any { it.isNotBlank() }
        return ver == versionStamp(context) && libs
    }

    /** Extract the bundled payload into [PAYLOAD_DIR]. */
    fun stage(context: Context): Boolean {
        val tmp = File(context.cacheDir, "applist_payload")
        tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            context.assets.open(PAYLOAD_ASSET).use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(tmp, entry.name)
                        // zip-slip guard
                        if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                            return false
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: Throwable) {
            tmp.deleteRecursively()
            return false
        }
        val ok = Shell.cmd(
            "rm -rf $PAYLOAD_DIR",
            "mkdir -p $PAYLOAD_DIR",
            "cp -rf '${tmp.absolutePath}'/. $PAYLOAD_DIR/",
            "echo -n ${versionStamp(context)} > $PAYLOAD_DIR/.ver",
            "chmod -R 755 /data/adb/applist"
        ).exec().isSuccess
        tmp.deleteRecursively()
        return ok
    }

    /** `<first 32 hex chars of SHA-256 of the signing certificate>:<package>` */
    private fun identity(context: Context): String? = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
        )
        val cert = info.signingInfo?.signingCertificateHistory?.lastOrNull()?.toByteArray()
            ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(cert)
            .joinToString("") { "%02x".format(it) }.substring(0, 32)
        "$hash:${context.packageName}"
    }.getOrNull()

    /** Publish who the manager is, so the service only talks to (and obeys) this app. */
    fun publishIdentity(context: Context): Boolean {
        val id = identity(context) ?: return false
        if (id.length > 91) return false // system property value limit
        return Shell.cmd("magisk resetprop -p $IDENTITY_PROP '$id'").exec().isSuccess
    }

    /** Turn the feature on/off. Returns false if staging failed. Takes effect after a reboot. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled) {
            if (!isStaged(context) && !stage(context)) return false
            if (!publishIdentity(context)) return false
        }
        Config.applistHide = enabled
        return true
    }

    fun state(context: Context): AppListState {
        if (!Config.applistHide) return AppListState.DISABLED
        if (!Config.zygisk) return AppListState.ZYGISK_OFF
        if (AppListClient.isConnected) {
            return when (AppListClient.workMode) {
                AppListClient.WORK_MODE_OK -> AppListState.ACTIVE
                AppListClient.WORK_MODE_NO_HOOKS -> AppListState.CONFLICT
                AppListClient.WORK_MODE_CRASHED -> AppListState.CRASHED
                else -> AppListState.LOADING
            }
        }
        return if (isStaged(context)) AppListState.WAITING else AppListState.NOT_STAGED
    }

}
