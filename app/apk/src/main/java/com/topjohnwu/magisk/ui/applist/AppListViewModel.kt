package com.topjohnwu.magisk.ui.applist

import android.content.Intent
import android.content.pm.ApplicationInfo
import com.topjohnwu.magisk.arch.AsyncLoadViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.ktx.reboot
import com.topjohnwu.magisk.ui.component.PendingRebootState
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.topjohnwu.magisk.core.R as CoreR

data class AppEntry(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val info: ApplicationInfo,
)

class AppListViewModel : AsyncLoadViewModel() {

    private val context get() = AppContext

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _model = MutableStateFlow(AppListModel.load(AppContext))
    val model: StateFlow<AppListModel> = _model.asStateFlow()

    private val _state = MutableStateFlow(AppListState.DISABLED)
    val state: StateFlow<AppListState> = _state.asStateFlow()

    private val _blocked = MutableStateFlow(0)
    val blocked: StateFlow<Int> = _blocked.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _showSystem = MutableStateFlow(false)
    val showSystem: StateFlow<Boolean> = _showSystem.asStateFlow()

    fun setQuery(q: String) { _query.value = q }
    fun setShowSystem(v: Boolean) { _showSystem.value = v }

    override suspend fun doLoadWork() {
        _loading.value = true
        withContext(Dispatchers.Default) {
            val pm = context.packageManager
            _apps.value = pm.getInstalledApplications(0)
                .filter { it.packageName != context.packageName }
                .map {
                    AppEntry(
                        label = it.loadLabel(pm).toString(),
                        packageName = it.packageName,
                        isSystem = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        info = it,
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
        refreshState()
        _loading.value = false
    }

    fun refreshState() {
        viewModelScope.launch {
            val (s, n) = withContext(Dispatchers.IO) {
                AppListEngine.state(context) to AppListClient.filterCount
            }
            _state.value = s
            _blocked.value = n
        }
    }

    val enabled get() = Config.applistHide

    fun setEnabled(on: Boolean) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { AppListEngine.setEnabled(context, on) }
            if (!ok) showSnackbar(CoreR.string.applist_stage_failed) else PendingRebootState.mark()
            refreshState()
            if (ok && on) pushConfig()
        }
    }

    private fun update(transform: (AppListModel) -> AppListModel) {
        _model.update(transform)
        AppListModel.save(context, _model.value)
        pushConfig()
    }

    fun setMode(mode: HideMode) = update { it.copy(mode = mode) }

    fun toggleHidden(pkg: String) = update {
        it.copy(hidden = if (pkg in it.hidden) it.hidden - pkg else it.hidden + pkg)
    }

    fun setConnected(pkg: String, apps: Set<String>) = update {
        it.copy(connected = it.connected + (pkg to apps))
    }

    fun setWhitelist(apps: Set<String>) = update { it.copy(whitelist = apps) }
    fun setBlind(apps: Set<String>) = update { it.copy(blind = apps) }
    fun setCategories(cats: Set<HideCategory>) = update { it.copy(categories = cats) }
    fun setAppCategories(pkg: String, cats: Set<HideCategory>) = update {
        it.copy(appCategories = it.appCategories + (pkg to cats))
    }

    /** Write the current model into the running service (no-op while it is not connected). */
    private fun pushConfig() {
        val installed = _apps.value.map { it.packageName }
        val model = _model.value
        viewModelScope.launch(Dispatchers.IO) {
            if (!AppListClient.isConnected) return@launch
            val json = AppListConfigBuilder.build(AppListClient.readConfig(), model, installed)
            AppListClient.writeConfig(json)
        }
    }

    // --- "Apply now" ---

    fun restartLauncher() {
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName ?: return
        forceStop(home)
    }

    fun restartSystemUi() = forceStop("com.android.systemui")

    private fun forceStop(pkg: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AppListClient.forceStop(pkg)
            Shell.cmd("am force-stop $pkg").exec()
        }
    }

    fun rebootNow() = reboot()

}
