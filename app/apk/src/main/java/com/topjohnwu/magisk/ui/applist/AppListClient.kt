package com.topjohnwu.magisk.ui.applist

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import icu.nullptr.hidemyapplist.common.IHMAService
import timber.log.Timber

/**
 * Manager side of the app-list hiding service that the payload runs inside system_server.
 *
 * The service pushes its binder to [ServiceProvider] when this app becomes active (see
 * `UserService` in app/applist). The protocol (AIDL + config exchanged through pipes) is the one
 * of HMA-OSS, which the payload is built from.
 */
object AppListClient : IBinder.DeathRecipient {

    /** Must match `configVerCode` in app/applist/build.gradle.kts */
    const val CONFIG_VERSION = 93

    private const val PARCEL_TYPE_CONFIG = 1

    // Mirrors Constants.MANAGER_WORK_MODE_* of the payload
    const val WORK_MODE_UNKNOWN = 0
    const val WORK_MODE_OK = 1
    const val WORK_MODE_NO_HOOKS = 2
    const val WORK_MODE_LOADING = 3
    const val WORK_MODE_CRASHED = 4

    @Volatile
    private var service: IHMAService? = null
    private var provider: ServiceProvider? = null

    val isConnected get() = service?.asBinder()?.isBinderAlive == true

    fun link(provider: ServiceProvider, binder: IBinder) {
        this.provider = provider
        service = IHMAService.Stub.asInterface(binder)
        runCatching { binder.linkToDeath(this, 0) }
    }

    override fun binderDied() {
        service = null
        Timber.e("app-list service binder died")
    }

    val workMode: Int
        get() = runCatching { service?.managerWorkMode }.getOrNull() ?: WORK_MODE_UNKNOWN

    val filterCount: Int
        get() = runCatching { service?.filterCount }.getOrNull() ?: 0

    fun readConfig(): String? = runCatching {
        val fd = service?.readFD(PARCEL_TYPE_CONFIG) ?: return null
        ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }.getOrNull()

    fun writeConfig(json: String): Boolean = runCatching {
        val srv = service ?: return false
        val prov = provider ?: return false
        val uri = "content://${ServiceProvider.authority(prov)}".toUri()
        val pipe = prov.openPipeHelper(uri, "text/plain", null, json) { output, _, _, _, data ->
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(output).bufferedWriter(Charsets.UTF_8)
                    .apply { write(data); flush() }
            }.onFailure { Timber.e(it) }
        }
        srv.writeFD(PARCEL_TYPE_CONFIG, pipe)
        true
    }.getOrDefault(false)

    fun forceStop(packageName: String, userId: Int = 0) {
        runCatching { service?.forceStop(packageName, userId) }
    }
}

class ServiceProvider : ContentProvider() {
    override fun onCreate() = false
    override fun query(p0: Uri, p1: Array<out String>?, p2: String?, p3: Array<out String>?, p4: String?) = null
    override fun getType(p0: Uri) = null
    override fun insert(p0: Uri, p1: ContentValues?) = null
    override fun delete(p0: Uri, p1: String?, p2: Array<out String>?) = 0
    override fun update(p0: Uri, p1: ContentValues?, p2: String?, p3: Array<out String>?) = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Only system_server (package "android") may hand us the service binder
        if (callingPackage != "android") return null
        val binder = extras?.getBinder("binder") ?: return null
        AppListClient.link(this, binder)
        return Bundle()
    }

    companion object {
        fun authority(provider: ServiceProvider) =
            "${provider.context!!.packageName}.applist.ServiceProvider"
    }
}
