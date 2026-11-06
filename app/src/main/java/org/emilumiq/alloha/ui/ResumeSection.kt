package org.emilumiq.alloha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.emilumiq.alloha.player.PlaybackSnapshot
import org.emilumiq.alloha.ui.components.SectionCard
import org.emilumiq.alloha.ui.components.formatDuration

@Composable
fun ResumeSection(
    snapshot: PlaybackSnapshot,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    SectionCard(title = "Незавершённый просмотр") {
        Text(text = snapshot.title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = listOfNotNull(snapshot.episodeLabel, formatDuration(snapshot.positionMs))
                .joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Text("  Продолжить")
            }
            TextButton(onClick = onDismiss) { Text("Скрыть") }
        }
    }
}
