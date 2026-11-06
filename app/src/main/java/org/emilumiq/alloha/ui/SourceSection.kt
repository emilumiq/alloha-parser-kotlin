package org.emilumiq.alloha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.emilumiq.alloha.ui.components.SectionCard

@Composable
fun SourceSection(
    token: String,
    kp: String,
    loading: Boolean,
    stale: Boolean,
    expanded: Boolean,
    onToggleExpanded: (Boolean) -> Unit,
    onTokenChange: (String) -> Unit,
    onKpChange: (String) -> Unit,
    onLoad: () -> Unit,
) {
    SectionCard(title = "Источник") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("KP ID $kp", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "token ${maskToken(token)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onToggleExpanded(!expanded) }) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.Close else Icons.Rounded.Edit,
                    contentDescription = if (expanded) "Свернуть" else "Изменить",
                )
            }
        }
        if (expanded) {
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                label = { Text("Alloha token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = kp,
                onValueChange = onKpChange,
                label = { Text("KP ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onLoad,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Загрузить")
                }
            }
        }
        if (stale) {
            Text(
                text = "Показаны сохранённые данные, обновляем в фоне",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun maskToken(token: String): String =
    if (token.length <= 8) "••••" else "${token.take(4)}••••${token.takeLast(4)}"
