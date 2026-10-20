package com.topjohnwu.magisk.ui.applist

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.R as CoreR

private fun HideCategory.labelRes() = when (this) {
    HideCategory.ROOT -> CoreR.string.applist_preset_root
    HideCategory.XPOSED -> CoreR.string.applist_preset_xposed
    HideCategory.DETECTOR -> CoreR.string.applist_preset_detector
    HideCategory.CUSTOM_ROM -> CoreR.string.applist_preset_customrom
    HideCategory.SUSPICIOUS -> CoreR.string.applist_preset_suspicious
    HideCategory.ACCESSIBILITY -> CoreR.string.applist_preset_accessibility
    HideCategory.SHIZUKU -> CoreR.string.applist_preset_shizuku
}

private fun AppListState.titleRes() = when (this) {
    AppListState.DISABLED -> CoreR.string.applist_state_disabled_title
    AppListState.ZYGISK_OFF -> CoreR.string.applist_state_zygisk_title
    AppListState.NOT_STAGED, AppListState.WAITING -> CoreR.string.applist_state_waiting_title
    AppListState.LOADING -> CoreR.string.applist_state_loading_title
    AppListState.ACTIVE -> CoreR.string.applist_state_active_title
    AppListState.CONFLICT -> CoreR.string.applist_state_conflict_title
    AppListState.CRASHED -> CoreR.string.applist_state_crashed_title
}

private fun AppListState.descRes() = when (this) {
    AppListState.DISABLED -> CoreR.string.applist_state_disabled_desc
    AppListState.ZYGISK_OFF -> CoreR.string.applist_state_zygisk_desc
    AppListState.NOT_STAGED -> CoreR.string.applist_state_notstaged_desc
    AppListState.WAITING -> CoreR.string.applist_state_waiting_desc
    AppListState.LOADING -> CoreR.string.applist_state_loading_desc
    AppListState.ACTIVE -> CoreR.string.applist_state_active_desc
    AppListState.CONFLICT -> CoreR.string.applist_state_conflict_desc
    AppListState.CRASHED -> CoreR.string.applist_state_crashed_desc
}

private sealed interface Dialog {
    data object Whitelist : Dialog
    data object Blind : Dialog
    data object Categories : Dialog
    data class Connected(val pkg: String, val label: String) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    viewModel: AppListViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val showSystem by viewModel.showSystem.collectAsStateWithLifecycle()

    var enabled by remember { mutableStateOf(viewModel.enabled) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }

    val visibleApps = remember(apps, query, showSystem, model.hidden) {
        apps.filter { app ->
            (showSystem || !app.isSystem || app.packageName in model.hidden) &&
                (query.isBlank() || app.label.contains(query, true) ||
                    app.packageName.contains(query, true))
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.applist_hide_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        if (loading) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (state) {
                            AppListState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
                            AppListState.CONFLICT, AppListState.CRASHED ->
                                MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(state.titleRes()), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(state.descRes()), style = MaterialTheme.typography.bodyMedium)
                        if (state == AppListState.ACTIVE) {
                            Text(
                                stringResource(
                                    CoreR.string.applist_active_line,
                                    model.hidden.size, blocked.toString()
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state == AppListState.WAITING || state == AppListState.NOT_STAGED) {
                                Button(onClick = { viewModel.rebootNow() }) {
                                    Text(stringResource(CoreR.string.applist_reboot_now))
                                }
                            }
                            TextButton(onClick = { viewModel.refreshState() }) {
                                Text(stringResource(CoreR.string.applist_recheck))
                            }
                        }
                    }
                }
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(CoreR.string.applist_hide_title)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (enabled) CoreR.string.applist_hide_on else CoreR.string.applist_hide_off
                            )
                        )
                    },
                    trailingContent = {
                        Switch(checked = enabled, onCheckedChange = {
                            enabled = it
                            viewModel.setEnabled(it)
                        })
                    }
                )
                Text(
                    stringResource(CoreR.string.applist_reboot_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            item {
                Text(stringResource(CoreR.string.applist_mode_header), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = model.mode == HideMode.BLACKLIST,
                        onClick = { viewModel.setMode(HideMode.BLACKLIST) },
                        label = { Text(stringResource(CoreR.string.applist_mode_blacklist)) }
                    )
                    FilterChip(
                        selected = model.mode == HideMode.WHITELIST,
                        onClick = { viewModel.setMode(HideMode.WHITELIST) },
                        label = { Text(stringResource(CoreR.string.applist_mode_whitelist)) }
                    )
                }
                Text(
                    stringResource(
                        if (model.mode == HideMode.BLACKLIST) CoreR.string.applist_mode_blacklist_desc
                        else CoreR.string.applist_mode_whitelist_desc
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item {
                Text(stringResource(CoreR.string.applist_actions_header), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.restartLauncher() }) {
                        Text(stringResource(CoreR.string.applist_restart_launcher))
                    }
                    OutlinedButton(onClick = { viewModel.restartSystemUi() }) {
                        Text(stringResource(CoreR.string.applist_restart_systemui))
                    }
                }
            }

            item {
                Text(stringResource(CoreR.string.applist_options_header), style = MaterialTheme.typography.titleSmall)
                ListItem(
                    modifier = Modifier.clickable { dialog = Dialog.Whitelist },
                    headlineContent = { Text(stringResource(CoreR.string.applist_whitelist_title)) },
                    supportingContent = { Text(stringResource(CoreR.string.applist_whitelist_desc)) },
                    trailingContent = { Text(model.whitelist.size.toString()) }
                )
                ListItem(
                    modifier = Modifier.clickable { dialog = Dialog.Blind },
                    headlineContent = { Text(stringResource(CoreR.string.applist_blind_title)) },
                    supportingContent = { Text(stringResource(CoreR.string.applist_blind_desc)) },
                    trailingContent = { Text(model.blind.size.toString()) }
                )
                ListItem(
                    modifier = Modifier.clickable { dialog = Dialog.Categories },
                    headlineContent = { Text(stringResource(CoreR.string.applist_presets_title)) },
                    supportingContent = { Text(stringResource(CoreR.string.applist_presets_desc)) },
                    trailingContent = {
                        Text(stringResource(CoreR.string.applist_categories_count, model.categories.size))
                    }
                )
            }

            item {
                Text(
                    stringResource(
                        if (model.mode == HideMode.BLACKLIST) CoreR.string.applist_apps_header
                        else CoreR.string.applist_apps_header_whitelist
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    placeholder = { Text(stringResource(CoreR.string.hide_filter_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showSystem, onCheckedChange = viewModel::setShowSystem)
                    Text(stringResource(CoreR.string.show_system_app))
                }
            }

            if (visibleApps.isEmpty()) {
                item { Text(stringResource(CoreR.string.applist_empty_title)) }
            }
            items(visibleApps, key = { it.packageName }) { app ->
                val hidden = app.packageName in model.hidden
                ListItem(
                    modifier = Modifier.clickable(enabled = hidden) {
                        dialog = Dialog.Connected(app.packageName, app.label)
                    },
                    leadingContent = {
                        Image(
                            painter = rememberDrawablePainter(
                                remember(app.packageName) {
                                    app.info.loadIcon(AppContext.packageManager)
                                }
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )
                    },
                    headlineContent = { Text(app.label) },
                    supportingContent = {
                        Column {
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            val modeBlack = model.mode == HideMode.BLACKLIST
                            Text(
                                stringResource(
                                    if (hidden == modeBlack) CoreR.string.applist_row_hidden
                                    else CoreR.string.applist_row_visible
                                ),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    trailingContent = {
                        Switch(checked = hidden, onCheckedChange = { viewModel.toggleHidden(app.packageName) })
                    }
                )
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    when (val d = dialog) {
        Dialog.Whitelist -> AppPickerDialog(
            title = stringResource(CoreR.string.applist_whitelist_title),
            description = stringResource(CoreR.string.applist_whitelist_desc),
            apps = apps, initial = model.whitelist,
            onDismiss = { dialog = null },
            onConfirm = { viewModel.setWhitelist(it); dialog = null }
        )
        Dialog.Blind -> AppPickerDialog(
            title = stringResource(CoreR.string.applist_blind_title),
            description = stringResource(CoreR.string.applist_blind_desc),
            apps = apps, initial = model.blind,
            onDismiss = { dialog = null },
            onConfirm = { viewModel.setBlind(it); dialog = null }
        )
        Dialog.Categories -> CategoryDialog(
            title = stringResource(CoreR.string.applist_presets_title),
            initial = model.categories,
            onDismiss = { dialog = null },
            onConfirm = { viewModel.setCategories(it); dialog = null }
        )
        is Dialog.Connected -> AppPickerDialog(
            title = stringResource(CoreR.string.applist_connected_title),
            description = stringResource(CoreR.string.applist_connected_desc, d.label),
            apps = apps.filter { it.packageName != d.pkg },
            initial = model.connected[d.pkg].orEmpty(),
            onDismiss = { dialog = null },
            onConfirm = { viewModel.setConnected(d.pkg, it); dialog = null }
        )
        null -> {}
    }
}

@Composable
private fun AppPickerDialog(
    title: String,
    description: String?,
    apps: List<AppEntry>,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val shown = remember(apps, query) {
        apps.filter {
            query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
        }.sortedWith(compareBy({ it.packageName !in initial }, { it.label.lowercase() }))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(stringResource(CoreR.string.hide_filter_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.height(320.dp)) {
                    items(shown, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (app.packageName in selected) selected - app.packageName
                                else selected + app.packageName
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = app.packageName in selected,
                                onCheckedChange = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Column {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
private fun CategoryDialog(
    title: String,
    initial: Set<HideCategory>,
    onDismiss: () -> Unit,
    onConfirm: (Set<HideCategory>) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                HideCategory.entries.forEach { cat ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (cat in selected) selected - cat else selected + cat
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = cat in selected,
                            onCheckedChange = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(stringResource(cat.labelRes()))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
