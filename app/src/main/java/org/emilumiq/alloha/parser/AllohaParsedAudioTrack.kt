package org.emilumiq.alloha.parser

data class AllohaParsedAudioTrack(
    val track: AllohaAudioTrack,
    val qualities: LinkedHashMap<String, String>,
)
