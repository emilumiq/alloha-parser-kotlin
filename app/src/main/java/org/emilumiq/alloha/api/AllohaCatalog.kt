package org.emilumiq.alloha.api

data class AllohaCatalog(
    val title: String,
    val isSerial: Boolean,
    val movieIframeUrl: String?,
    val seasons: List<AllohaSeason>,
)

data class AllohaSeason(
    val number: Int,
    val episodes: List<AllohaEpisode>,
)

data class AllohaEpisode(
    val number: Int,
    val translations: List<AllohaTranslation>,
)

data class AllohaTranslation(
    val id: String,
    val name: String,
    val iframeUrl: String,
)
