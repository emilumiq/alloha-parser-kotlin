package org.emilumiq.alloha.ui

import org.emilumiq.alloha.api.AllohaCatalog
import org.emilumiq.alloha.api.AllohaEpisode
import org.emilumiq.alloha.api.AllohaSeason
import org.emilumiq.alloha.api.AllohaTranslation
import org.emilumiq.alloha.parser.AllohaAudioTrack
import org.emilumiq.alloha.parser.AllohaSubtitleTrack
import org.emilumiq.alloha.player.PlaybackSnapshot

enum class SessionPhase { IDLE, OPENING, READY, RESTORING, ERROR }

data class AllohaUiState(
    val token: String = "",
    val kp: String = "",
    val catalog: AllohaCatalog? = null,
    val catalogLoading: Boolean = false,
    val catalogStale: Boolean = false,
    val seasonIndex: Int = 0,
    val episodeIndex: Int = 0,
    val translationIndex: Int = 0,
    val phase: SessionPhase = SessionPhase.IDLE,
    val status: String = "",
    val audioTracks: List<AllohaAudioTrack> = emptyList(),
    val selectedAudioId: String? = null,
    val qualities: List<String> = emptyList(),
    val selectedQuality: String? = null,
    val subtitleTracks: List<AllohaSubtitleTrack> = emptyList(),
    val selectedSubtitleIndex: Int? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val resumeCandidate: PlaybackSnapshot? = null,
    val errorMessage: String? = null,
    val playerVisible: Boolean = false,
) {
    val selectedSeason: AllohaSeason? get() = catalog?.seasons?.getOrNull(seasonIndex)

    val selectedEpisode: AllohaEpisode? get() = selectedSeason?.episodes?.getOrNull(episodeIndex)

    val selectedTranslation: AllohaTranslation?
        get() = selectedEpisode?.translations?.getOrNull(translationIndex)

    val canPlaySelected: Boolean
        get() = if (catalog?.isSerial == true) {
            selectedTranslation?.iframeUrl?.isNotBlank() == true
        } else {
            catalog?.movieIframeUrl?.isNotBlank() == true
        }
}
