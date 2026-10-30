package com.topjohnwu.magisk.ui.settings

import android.webkit.URLUtil
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.topjohnwu.magisk.core.utils.BootloopProtector
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.core.content.pm.ShortcutManagerCompat
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.isRunningAsStub
import com.topjohnwu.magisk.core.model.ColorMode
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.ui.component.AdaptiveColumns
import com.topjohnwu.magisk.ui.component.MagiskDialog
import com.topjohnwu.magisk.ui.component.SettingsArrow
import com.topjohnwu.magisk.ui.component.SettingsDropdown
import com.topjohnwu.magisk.ui.component.SettingsSwitch
import com.topjohnwu.magisk.ui.ThemePalette
import com.topjohnwu.magisk.ui.ThemeState
import com.topjohnwu.magisk.ui.component.SmallTitle
import com.topjohnwu.magisk.ui.component.onlyHorizontal
import com.topjohnwu.magisk.ui.component.rememberBiometricAuth
import com.topjohnwu.magisk.ui.component.rememberNotificationPermissionLauncher
import com.topjohnwu.magisk.ui.component.verticalScrollbar
import com.topjohnwu.magisk.core.R as CoreR

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val requireAuth = rememberBiometricAuth()
    val enableCheckUpdate = rememberNotificationPermissionLauncher { viewModel.setCheckUpdate(true) }

    LifecycleResumeEffect(viewModel) {
        viewModel.refreshEnvironment()
        onPauseOrDispose {}
    }

    SettingsContent(
        uiState = uiState,
        onOpenSystemLocale = {
            context.startActivity(LocaleSetting.localeSettingsIntent)
        },
        onLanguageSelected = viewModel::setLanguage,
        onColorModeSelected = viewModel::setColorMode,
        onUpdateChannelSelected = viewModel::setUpdateChannel,
        onOpenCustomUrlDialog = viewModel::openCustomChannelUrlDialog,
        onSaveCustomUrl = viewModel::saveCustomChannelUrl,
        onToggleDoh = viewModel::setDoh,
        // Update notifications need the notification permission
        onToggleCheckUpdate = { enabled ->
            if (enabled) enableCheckUpdate() else viewModel.setCheckUpdate(false)
        },
        onOpenDownloadPathDialog = viewModel::openDownloadPathDialog,
        onSaveDownloadPath = viewModel::saveDownloadPath,
        onToggleRandomPackageName = viewModel::setRandomPackageName,
        onAddShortcut = viewModel::requestAddShortcut,
        onCreateHosts = viewModel::createHosts,
        onToggleZygisk = viewModel::toggleZygisk,
        onToggleDenyList = viewModel::toggleDenyList,
        // Drop taps while a screen transition runs, so a quick second tap can't push again
        onNavigateToDenyList = dropUnlessResumed { viewModel.navigateToDenyList() },
        onToggleSuAuth = { newValue ->
            requireAuth(force = true) {
                viewModel.setSuAuth(newValue)
            }
        },
        onMultiuserModeSelected = viewModel::setMultiuserMode,
        onNamespaceModeSelected = viewModel::setNamespaceMode,
        onNotificationModeSelected = viewModel::setNotificationMode,
        onToggleSuRestrict = viewModel::setSuRestrict,
        onDismissDialog = viewModel::dismissDialog,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onOpenSystemLocale: () -> Unit,
    onLanguageSelected: (Int) -> Unit,
    onColorModeSelected: (Int) -> Unit,
    onUpdateChannelSelected: (Int) -> Unit,
    onOpenCustomUrlDialog: () -> Unit,
    onSaveCustomUrl: (String) -> Unit,
    onToggleDoh: (Boolean) -> Unit,
    onToggleCheckUpdate: (Boolean) -> Unit,
    onOpenDownloadPathDialog: () -> Unit,
    onSaveDownloadPath: (String) -> Unit,
    onToggleRandomPackageName: (Boolean) -> Unit,
    onAddShortcut: () -> Unit,
    onCreateHosts: () -> Unit,
    onToggleZygisk: (Boolean) -> Unit,
    onToggleDenyList: (Boolean) -> Unit,
    onNavigateToDenyList: () -> Unit,
    onToggleSuAuth: (Boolean) -> Unit,
    onMultiuserModeSelected: (Int) -> Unit,
    onNamespaceModeSelected: (Int) -> Unit,
    onNotificationModeSelected: (Int) -> Unit,
    onToggleSuRestrict: (Boolean) -> Unit,
    onDismissDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollState = rememberScrollState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.settings)) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        // Without root, the Magisk and Superuser sections are hidden, so move the
        // App section to the second column to avoid leaving it empty.
        // Each section emits a title and a card, so wrap them in a Column to keep them together
        AdaptiveColumns(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .consumeWindowInsets(padding)
                .padding(padding.onlyHorizontal())
                .verticalScrollbar(
                    scrollState,
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 12.dp,
                        bottom = padding.calculateBottomPadding() + 12.dp,
                    ),
                )
                .verticalScroll(scrollState)
                .padding(
                    start = 16.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    end = 16.dp,
                    bottom = padding.calculateBottomPadding() + 12.dp,
                ),
            spacing = 16.dp,
            first = {
                Column {
                    CustomizationSection(
                        uiState = uiState,
                        onOpenSystemLocale = onOpenSystemLocale,
                        onLanguageSelected = onLanguageSelected,
                        onColorModeSelected = onColorModeSelected,
                        onAddShortcut = onAddShortcut,
                    )
                }
                if (uiState.hasRootSections) {
                    Column {
                        AppSettingsSection(
                            uiState = uiState,
                            onUpdateChannelSelected = onUpdateChannelSelected,
                            onOpenCustomUrlDialog = onOpenCustomUrlDialog,
                            onToggleDoh = onToggleDoh,
                            onToggleCheckUpdate = onToggleCheckUpdate,
                            onOpenDownloadPathDialog = onOpenDownloadPathDialog,
                            onToggleRandomPackageName = onToggleRandomPackageName,
                        )
                    }
                }
            },
            second = {
                if (!uiState.hasRootSections) {
                    Column {
                        AppSettingsSection(
                            uiState = uiState,
                            onUpdateChannelSelected = onUpdateChannelSelected,
                            onOpenCustomUrlDialog = onOpenCustomUrlDialog,
                            onToggleDoh = onToggleDoh,
                            onToggleCheckUpdate = onToggleCheckUpdate,
                            onOpenDownloadPathDialog = onOpenDownloadPathDialog,
                            onToggleRandomPackageName = onToggleRandomPackageName,
                        )
                    }
                }
                if (uiState.isMagiskInstalled) {
                    Column {
                        MagiskSection(
                            uiState = uiState,
                            onCreateHosts = onCreateHosts,
                            onToggleZygisk = onToggleZygisk,
                            onToggleDenyList = onToggleDenyList,
                            onNavigateToDenyList = onNavigateToDenyList,
                        )
                    }
                }
                if (uiState.isSuperUserAvailable) {
                    Column {
                        SuperuserSection(
                            uiState = uiState,
                            onToggleSuAuth = onToggleSuAuth,
                            onMultiuserModeSelected = onMultiuserModeSelected,
                            onNamespaceModeSelected = onNamespaceModeSelected,
                            onNotificationModeSelected = onNotificationModeSelected,
                            onToggleSuRestrict = onToggleSuRestrict,
                        )
                    }
                }
            },
        )
    }

    when (val dialog = uiState.activeDialog) {
        is SettingsDialog.CustomChannelUrl -> {
            UpdateChannelUrlDialog(
                initialUrl = dialog.initialUrl,
                onConfirm = onSaveCustomUrl,
                onDismiss = onDismissDialog,
            )
        }
        is SettingsDialog.DownloadPath -> {
            DownloadPathDialog(
                initialPath = dialog.initialPath,
                onConfirm = onSaveDownloadPath,
                onDismiss = onDismissDialog,
            )
        }
        null -> Unit
    }
}

// --- Customization ---

@Composable
private fun CustomizationSection(
    uiState: SettingsUiState,
    onOpenSystemLocale: () -> Unit,
    onLanguageSelected: (Int) -> Unit,
    onColorModeSelected: (Int) -> Unit,
    onAddShortcut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    SmallTitle(text = stringResource(CoreR.string.settings_customization))
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        if (uiState.useLocaleManager) {
            val appLocale = LocaleSetting.instance.appLocale
            SettingsArrow(
                title = stringResource(CoreR.string.language),
                summary = appLocale?.getDisplayName(appLocale)
                    ?: stringResource(CoreR.string.system_default),
                onClick = onOpenSystemLocale
            )
        } else {
            SettingsDropdown(
                title = stringResource(CoreR.string.language),
                items = uiState.availableLanguages,
                selectedIndex = uiState.selectedLanguageIndex,
                onSelectedIndexChange = onLanguageSelected
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // Color Mode
        val colorModes = stringArrayResource(CoreR.array.color_mode).asList()
        SettingsDropdown(
            title = stringResource(CoreR.string.settings_color_mode),
            items = if (uiState.isDynamicColorSupported) colorModes else colorModes.drop(3),
            selectedIndex = uiState.selectedColorModeIndex,
            onSelectedIndexChange = onColorModeSelected
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // Kitsune colour palette
        var themeOrdinal by remember { mutableIntStateOf(Config.themeOrdinal) }
        SettingsDropdown(
            title = stringResource(CoreR.string.theme_title),
            items = remember { ThemePalette.entries.map { it.label } },
            selectedIndex = themeOrdinal.coerceIn(0, ThemePalette.entries.size - 1),
            onSelectedIndexChange = { index ->
                themeOrdinal = index
                Config.themeOrdinal = index
                ThemeState.themeOrdinal = index
            }
        )

        var pureBlack by remember { mutableStateOf(Config.pureBlack) }
        SettingsSwitch(
            title = stringResource(CoreR.string.theme_pure_black),
            summary = stringResource(CoreR.string.theme_pure_black_desc),
            checked = pureBlack,
            onCheckedChange = {
                pureBlack = it
                Config.pureBlack = it
                ThemeState.pureBlack = it
            }
        )

        if (isRunningAsStub && ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            SettingsArrow(
                title = stringResource(CoreR.string.add_shortcut_title),
                summary = stringResource(CoreR.string.setting_add_shortcut_summary),
                onClick = onAddShortcut
            )
        }
    }
}

// --- App Settings ---

@Composable
private fun AppSettingsSection(
    uiState: SettingsUiState,
    onUpdateChannelSelected: (Int) -> Unit,
    onOpenCustomUrlDialog: () -> Unit,
    onToggleDoh: (Boolean) -> Unit,
    onToggleCheckUpdate: (Boolean) -> Unit,
    onOpenDownloadPathDialog: () -> Unit,
    onToggleRandomPackageName: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    SmallTitle(text = stringResource(CoreR.string.home_app_title))
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // Update Channel
        SettingsDropdown(
            title = stringResource(CoreR.string.settings_update_channel_title),
            items = stringArrayResource(CoreR.array.update_channel).asList(),
            selectedIndex = uiState.selectedUpdateChannelIndex,
            onSelectedIndexChange = onUpdateChannelSelected
        )

        // Update Channel URL (for custom channel)
        if (uiState.isCustomChannel) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            SettingsArrow(
                title = stringResource(CoreR.string.settings_update_custom),
                summary = uiState.customChannelUrl.ifBlank { null },
                onClick = onOpenCustomUrlDialog
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // DoH Toggle
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_doh_title),
            summary = stringResource(CoreR.string.settings_doh_description),
            checked = uiState.dohEnabled,
            onCheckedChange = onToggleDoh
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // Update Checker
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_check_update_title),
            summary = stringResource(CoreR.string.settings_check_update_summary),
            checked = uiState.checkUpdateEnabled,
            onCheckedChange = onToggleCheckUpdate
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // Download Path
        SettingsArrow(
            title = stringResource(CoreR.string.settings_download_path_title),
            summary = uiState.downloadPathDisplay,
            onClick = onOpenDownloadPathDialog
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // Random Package Name
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_random_name_title),
            summary = stringResource(CoreR.string.settings_random_name_description),
            checked = uiState.randomPackageNameEnabled,
            onCheckedChange = onToggleRandomPackageName
        )
    }
}

// --- Magisk ---

@Composable
private fun MagiskSection(
    uiState: SettingsUiState,
    onCreateHosts: () -> Unit,
    onToggleZygisk: (Boolean) -> Unit,
    onToggleDenyList: (Boolean) -> Unit,
    onNavigateToDenyList: () -> Unit,
    modifier: Modifier = Modifier
) {
    SmallTitle(text = stringResource(CoreR.string.magisk))
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // Systemless Hosts
        SettingsArrow(
            title = stringResource(CoreR.string.settings_hosts_title),
            summary = stringResource(CoreR.string.settings_hosts_summary),
            onClick = onCreateHosts
        )

        if (uiState.isZygiskSupported) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Zygisk
            SettingsSwitch(
                title = stringResource(CoreR.string.zygisk),
                summary = stringResource(
                    if (uiState.isZygiskPendingReboot) CoreR.string.reboot_apply_change
                    else CoreR.string.settings_zygisk_summary
                ),
                checked = uiState.zygiskEnabled,
                onCheckedChange = onToggleZygisk
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // DenyList
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_denylist_title),
                summary = stringResource(CoreR.string.settings_denylist_summary),
                checked = uiState.denyListEnabled,
                onCheckedChange = onToggleDenyList
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // DenyList Config
            SettingsArrow(
                title = stringResource(CoreR.string.settings_denylist_config_title),
                summary = stringResource(CoreR.string.settings_denylist_config_summary),
                onClick = onNavigateToDenyList
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // SuList: only the listed apps can see/use root
            val suListEnabled by viewModel.suListEnabled.collectAsStateWithLifecycle()
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_sulist_title),
                summary = stringResource(CoreR.string.settings_sulist_enabled),
                checked = suListEnabled,
                onCheckedChange = { viewModel.toggleSuList(it) }
            )

            if (suListEnabled) {
                var autoGrant by remember { mutableStateOf(Config.sulistAutoGrant) }
                SettingsSwitch(
                    title = stringResource(CoreR.string.settings_sulist_auto_grant_title),
                    summary = stringResource(CoreR.string.settings_sulist_auto_grant_summary),
                    checked = autoGrant,
                    onCheckedChange = {
                        autoGrant = it
                        Config.sulistAutoGrant = it
                    }
                )
                SettingsArrow(
                    title = stringResource(CoreR.string.settings_configure_sulist),
                    summary = stringResource(CoreR.string.settings_sulist_summary),
                    onClick = { viewModel.navigateToSuList() }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // App-list hiding (hides chosen apps from other apps' package queries)
            SettingsArrow(
                title = stringResource(CoreR.string.applist_hide_title),
                summary = stringResource(CoreR.string.applist_hide_desc),
                onClick = { viewModel.navigateToAppList() }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            SettingsArrow(
                title = stringResource(CoreR.string.module_review_title),
                summary = stringResource(CoreR.string.module_review_open),
                onClick = { viewModel.navigateToModuleReview() }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Bootloop protection
            val bootloopScope = rememberCoroutineScope()
            var bootloop by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                bootloop = withContext(Dispatchers.IO) { BootloopProtector.isEnabled() }
            }
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_bootloop_title),
                summary = stringResource(CoreR.string.settings_bootloop_summary),
                checked = bootloop,
                onCheckedChange = { on ->
                    bootloop = on
                    bootloopScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            if (on) BootloopProtector.enable() else BootloopProtector.disable()
                        }
                        if (!ok) bootloop = !on
                    }
                }
            )
        }
    }
}

// --- Superuser ---

@Composable
private fun SuperuserSection(
    uiState: SettingsUiState,
    onToggleSuAuth: (Boolean) -> Unit,
    onMultiuserModeSelected: (Int) -> Unit,
    onNamespaceModeSelected: (Int) -> Unit,
    onNotificationModeSelected: (Int) -> Unit,
    onToggleSuRestrict: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    SmallTitle(text = stringResource(CoreR.string.superuser))
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // Authentication
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_su_auth_title),
            summary = stringResource(
                if (uiState.isDeviceSecure) CoreR.string.settings_su_auth_summary
                else CoreR.string.settings_su_auth_insecure
            ),
            checked = uiState.suAuthEnabled,
            enabled = uiState.isDeviceSecure,
            onCheckedChange = onToggleSuAuth
        )

        if (uiState.isMultiuserSupported) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Multiuser Mode
            val multiuserSummaries = stringArrayResource(CoreR.array.multiuser_summary)
            SettingsDropdown(
                title = stringResource(CoreR.string.multiuser_mode),
                summary = multiuserSummaries.getOrElse(uiState.selectedMultiuserModeIndex) { "" },
                items = stringArrayResource(CoreR.array.multiuser_mode).asList(),
                selectedIndex = uiState.selectedMultiuserModeIndex,
                enabled = uiState.isMultiuserEditable,
                onSelectedIndexChange = onMultiuserModeSelected
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // Mount Namespace Mode
        val namespaceSummaries = stringArrayResource(CoreR.array.namespace_summary)
        SettingsDropdown(
            title = stringResource(CoreR.string.mount_namespace_mode),
            summary = namespaceSummaries.getOrElse(uiState.selectedNamespaceModeIndex) { "" },
            items = stringArrayResource(CoreR.array.namespace).asList(),
            selectedIndex = uiState.selectedNamespaceModeIndex,
            onSelectedIndexChange = onNamespaceModeSelected
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        // SU Notification
        SettingsDropdown(
            title = stringResource(CoreR.string.superuser_notification),
            items = stringArrayResource(CoreR.array.su_notification).asList(),
            selectedIndex = uiState.selectedNotificationModeIndex,
            onSelectedIndexChange = onNotificationModeSelected
        )

        // Restrict (version >= 30.1)
        if (uiState.isSuRestrictSupported) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_su_restrict_title),
                summary = stringResource(CoreR.string.settings_su_restrict_summary),
                checked = uiState.suRestrictEnabled,
                onCheckedChange = onToggleSuRestrict
            )
        }
    }
}

// --- Dialogs ---

@Composable
private fun UpdateChannelUrlDialog(
    initialUrl: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    val isValidUrl = remember(url) {
        url.isBlank() || (
            (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) &&
            URLUtil.isValidUrl(url)
        )
    }

    MagiskDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = stringResource(CoreR.string.settings_update_custom_msg),
        confirmText = stringResource(android.R.string.ok),
        confirmEnabled = isValidUrl,
        onConfirm = {
            if (isValidUrl) {
                onConfirm(url)
            }
        },
        dismissText = stringResource(android.R.string.cancel),
        onDismiss = onDismiss,
        scrollable = true,
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                isError = !isValidUrl,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DownloadPathDialog(
    initialPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var path by rememberSaveable(initialPath) { mutableStateOf(initialPath) }

    MagiskDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = stringResource(CoreR.string.settings_download_path_title),
        confirmText = stringResource(android.R.string.ok),
        onConfirm = {
            onConfirm(path.trim())
        },
        dismissText = stringResource(android.R.string.cancel),
        onDismiss = onDismiss,
        scrollable = true,
    ) {
        Column {
            Text(
                text = stringResource(CoreR.string.settings_download_path_message, MediaStoreUtils.fullPath(path)),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
