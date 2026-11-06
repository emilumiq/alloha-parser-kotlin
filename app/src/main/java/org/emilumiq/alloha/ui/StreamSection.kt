package org.emilumiq.alloha.ui

import androidx.compose.runtime.Composable
import org.emilumiq.alloha.parser.AllohaAudioTrack
import org.emilumiq.alloha.parser.AllohaSubtitleTrack
import org.emilumiq.alloha.ui.components.ChipRow
import org.emilumiq.alloha.ui.components.DropdownField
import org.emilumiq.alloha.ui.components.SectionCard

@Composable
fun StreamSection(
    audioTracks: List<AllohaAudioTrack>,
    qualities: List<String>,
    subtitles: List<AllohaSubtitleTrack>,
    selectedAudioId: String?,
    selectedQuality: String?,
    selectedSubtitleIndex: Int?,
    onAudioSelected: (String) -> Unit,
    onQualitySelected: (String) -> Unit,
    onSubtitleSelected: (Int?) -> Unit,
) {
    if (audioTracks.size <= 1 && qualities.isEmpty() && subtitles.isEmpty()) return
    SectionCard(title = "Поток") {
        if (audioTracks.size > 1) {
            val selectedIndex = audioTracks.indexOfFirst { it.id == selectedAudioId }
                .coerceAtLeast(0)
            DropdownField(
                label = "Аудиодорожка",
                value = audioTracks.getOrNull(selectedIndex)?.label ?: "—",
                options = audioTracks.map { it.label },
                onSelected = { index -> audioTracks.getOrNull(index)?.let { onAudioSelected(it.id) } },
            )
        }
        if (subtitles.isNotEmpty()) {
            DropdownField(
                label = "Субтитры",
                value = selectedSubtitleIndex?.let { subtitles.getOrNull(it)?.label } ?: "Выкл",
                options = listOf("Выкл") + subtitles.map { it.label },
                onSelected = { index ->
                    onSubtitleSelected(if (index == 0) null else index - 1)
                },
            )
        }
        ChipRow(
            title = "Качество",
            options = qualities.map { it to it },
            selectedKey = selectedQuality,
            onSelect = onQualitySelected,
        )
    }
}
