package org.emilumiq.alloha.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.emilumiq.alloha.ui.components.DropdownField
import org.emilumiq.alloha.ui.components.SectionCard

@Composable
fun CatalogSection(
    state: AllohaUiState,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (Int) -> Unit,
    onTranslationSelected: (Int) -> Unit,
    onWatch: () -> Unit,
) {
    val catalog = state.catalog ?: return
    SectionCard(title = "Материал") {
        Text(text = catalog.title, style = MaterialTheme.typography.titleMedium)
        if (catalog.isSerial) {
            val season = state.selectedSeason
            val episode = state.selectedEpisode
            DropdownField(
                label = "Сезон",
                value = season?.let { "Сезон ${it.number}" } ?: "—",
                options = catalog.seasons.map { "Сезон ${it.number}" },
                onSelected = onSeasonSelected,
            )
            DropdownField(
                label = "Серия",
                value = episode?.let { "Серия ${it.number}" } ?: "—",
                options = season?.episodes?.map { "Серия ${it.number}" }.orEmpty(),
                onSelected = onEpisodeSelected,
            )
            DropdownField(
                label = "Озвучка сайта",
                value = state.selectedTranslation?.name ?: "—",
                options = episode?.translations?.map { it.name }.orEmpty(),
                onSelected = onTranslationSelected,
            )
        }
        Button(
            onClick = onWatch,
            enabled = state.canPlaySelected &&
                state.phase != SessionPhase.OPENING &&
                state.phase != SessionPhase.RESTORING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = if (state.playerVisible) Icons.Rounded.Replay else Icons.Rounded.PlayArrow,
                contentDescription = null,
            )
            Text(if (state.playerVisible) "  Перезапустить" else "  Смотреть")
        }
    }
}
