package com.insta.reelsoff.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.insta.reelsoff.R
import com.insta.reelsoff.data.DailyCount

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onStartCapture: () -> Unit,
    onBlockReelsChanged: (Boolean) -> Unit,
    onBlockExploreChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ServiceCard(state.serviceEnabled, onOpenAccessibilitySettings)

        if (state.degraded) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.degraded_warning),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        TodayCard(state.todayReels, state.todayExplore)

        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleMedium)
        History(state.history)

        SwitchRow(
            label = stringResource(R.string.block_reels),
            checked = state.settings.blockReels,
            onCheckedChange = onBlockReelsChanged,
        )
        SwitchRow(
            label = stringResource(R.string.block_explore),
            checked = state.settings.blockExplore,
            onCheckedChange = onBlockExploreChanged,
        )

        Button(onClick = onStartCapture, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.start_capture))
        }
        Text(stringResource(R.string.capture_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ServiceCard(enabled: Boolean, onOpenSettings: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(if (enabled) R.string.service_on else R.string.service_off),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!enabled) {
                Button(onClick = onOpenSettings) {
                    Text(stringResource(R.string.open_accessibility_settings))
                }
            }
            Text(stringResource(R.string.battery_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TodayCard(reels: Int, explore: Int) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.today), style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(R.string.reels)} : $reels")
            Text("${stringResource(R.string.explore)} : $explore")
        }
    }
}

@Composable
private fun History(history: List<DailyCount>) {
    val maximum = (history.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1)

    Row(
        modifier = Modifier.fillMaxWidth().height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        history.forEach { day ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((100.dp * day.total / maximum).coerceAtLeast(2.dp))
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(220.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
