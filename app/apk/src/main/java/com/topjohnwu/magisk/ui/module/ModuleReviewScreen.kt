package com.topjohnwu.magisk.ui.module

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.topjohnwu.magisk.core.R as CoreR

private fun Context.displayName(uri: Uri): String =
    runCatching {
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "module.zip"

/**
 * Collect module zips, review the list, then hand it to the bulk-flash flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleReviewScreen(onBack: () -> Unit, onInstall: (List<String>) -> Unit) {
    val context = LocalContext.current
    val picked = remember { mutableStateListOf<Uri>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.filter { it !in picked }.forEach { picked.add(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.module_review_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picker.launch("application/zip") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(CoreR.string.module_review_add))
            }
            if (picked.isEmpty()) {
                Text(stringResource(CoreR.string.module_review_empty))
            } else {
                Text(
                    if (picked.size == 1) stringResource(CoreR.string.module_review_count_one)
                    else stringResource(CoreR.string.module_review_count_many, picked.size)
                )
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(picked.toList(), key = { it.toString() }) { uri ->
                        Card {
                            ListItem(
                                headlineContent = { Text(context.displayName(uri)) },
                                trailingContent = {
                                    IconButton(onClick = { picked.remove(uri) }) {
                                        Icon(Icons.Rounded.Close, contentDescription = stringResource(CoreR.string.module_review_remove))
                                    }
                                }
                            )
                        }
                    }
                }
                Button(
                    onClick = { onInstall(picked.map { it.toString() }) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(CoreR.string.module_review_install_fmt, picked.size)) }
            }
        }
    }
}
