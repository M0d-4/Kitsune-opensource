package com.topjohnwu.magisk.ui.flash

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.magisk.core.tasks.BulkFlashResult
import com.topjohnwu.magisk.core.tasks.BulkFlashZip
import com.topjohnwu.superuser.CallbackList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BulkFlashViewModel : BaseViewModel() {

    data class UiState(
        val running: Boolean = true,
        val current: Int = 0,
        val total: Int = 0,
        val currentName: String = "",
        val results: List<BulkFlashResult> = emptyList(),
        val console: List<String> = emptyList(),
    ) {
        val succeeded get() = results.count { it.success }
        val failed get() = results.count { !it.success }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var started = false

    private val console = object : CallbackList<String>() {
        override fun onAddElement(e: String?) {
            e ?: return
            _state.update { it.copy(console = it.console + e) }
        }
    }

    /** Starts installing [uris]; calling it again (e.g. after rotation) is a no-op. */
    fun start(uris: List<Uri>) {
        if (started) return
        started = true
        if (uris.isEmpty()) {
            console.add(AppContext.getString(CoreR.string.bulk_flash_no_modules))
            _state.update { it.copy(running = false) }
            return
        }
        _state.update { it.copy(total = uris.size) }
        viewModelScope.launch {
            val results = BulkFlashZip(
                uris = uris,
                console = console,
                logs = mutableListOf(),
                onProgress = { current, total, name ->
                    _state.update { it.copy(current = current, total = total, currentName = name) }
                },
            ).exec()
            _state.update { it.copy(running = false, results = results) }
        }
    }
}
