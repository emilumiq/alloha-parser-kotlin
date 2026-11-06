package org.emilumiq.alloha.player

data class PlaybackSnapshot(
    val title: String,
    val episodeLabel: String?,
    val iframeUrl: String,
    val audioId: String?,
    val audioLabel: String?,
    val qualityLabel: String?,
    val positionMs: Long,
    val durationMs: Long,
    val wasPlaying: Boolean,
    val updatedAtMs: Long,
)
