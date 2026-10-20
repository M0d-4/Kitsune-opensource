package com.topjohnwu.magisk.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.ktx.reboot
import com.topjohnwu.magisk.core.utils.PendingReboot
import com.topjohnwu.magisk.core.R as CoreR

/** Compose-observable mirror of [PendingReboot]. */
object PendingRebootState {
    var pending by mutableStateOf(false)
        private set

    fun mark() {
        PendingReboot.mark()
        pending = true
    }

    fun refresh() {
        pending = PendingReboot.isPending()
    }
}

/** "Reboot to apply changes" banner, shown while a change is waiting for a reboot. */
@Composable
fun PendingRebootBanner(modifier: Modifier = Modifier) {
    LifecycleResumeEffect(Unit) {
        PendingRebootState.refresh()
        onPauseOrDispose { }
    }
    if (!PendingRebootState.pending) return

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(CoreR.string.reboot_apply_change),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            if (Info.isRooted) {
                Button(onClick = { reboot() }) { Text(stringResource(CoreR.string.reboot)) }
            }
        }
    }
}
