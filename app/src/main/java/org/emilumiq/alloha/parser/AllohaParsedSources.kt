package org.emilumiq.alloha.parser

data class AllohaParsedSources(
    val audioTracks: List<AllohaParsedAudioTrack>,
    val subtitles: List<AllohaSubtitleTrack>,
)
