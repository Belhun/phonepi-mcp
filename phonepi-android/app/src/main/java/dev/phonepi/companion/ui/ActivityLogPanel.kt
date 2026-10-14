package dev.phonepi.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.phonepi.companion.data.ActivityLogEntity
import dev.phonepi.companion.log.ActivityLog
import java.text.DateFormat
import java.util.Date

@Composable
fun ActivityLogPanel(modifier: Modifier = Modifier) {
    val entries by ActivityLog.observeRecent(150).collectAsState(initial = emptyList())
    val timeFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Recent activity", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { ActivityLog.clear() }) {
                Text("Clear")
            }
        }

        if (entries.isEmpty()) {
            Text(
                text = "Tool runs and connection events will show here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                entries.forEach { entry ->
                    ActivityLogRow(entry, timeFormat)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun ActivityLogRow(entry: ActivityLogEntity, timeFormat: DateFormat) {
    val statusColor = when (entry.status) {
        "success" -> MaterialTheme.colorScheme.primary
        "error" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = timeFormat.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = entry.status.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
        )
        Text(
            text = entry.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
