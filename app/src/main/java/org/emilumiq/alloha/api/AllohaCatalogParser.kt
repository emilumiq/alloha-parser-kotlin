package org.emilumiq.alloha.api

import org.json.JSONObject

object AllohaCatalogParser {
    fun parse(body: String): AllohaCatalog {
        val root = try {
            JSONObject(body)
        } catch (error: Exception) {
            throw AllohaApiException("Некорректный ответ API", error)
        }
        val data = root.optJSONObject("data")
            ?: throw AllohaApiException(root.optNonBlank("message") ?: "API не вернул данные")

        val seasons = data.optJSONObject("seasons")?.let(::parseSeasons).orEmpty()
        val movieIframe = data.optNonBlank("iframe")?.normalizeUrl()
        if (seasons.isEmpty() && movieIframe == null) {
            throw AllohaApiException("Для этого материала нет источника")
        }

        return AllohaCatalog(
            title = data.optNonBlank("name") ?: "Без названия",
            isSerial = seasons.isNotEmpty(),
            movieIframeUrl = movieIframe,
            seasons = seasons,
        )
    }

    private fun parseSeasons(seasonsNode: JSONObject): List<AllohaSeason> {
        val seasons = mutableListOf<AllohaSeason>()
        val keys = seasonsNode.keys()
        var position = 0
        while (keys.hasNext()) {
            val key = keys.next()
            position++
            val seasonNode = seasonsNode.optJSONObject(key) ?: continue
            val episodesNode = seasonNode.optJSONObject("episodes") ?: continue
            val episodes = parseEpisodes(episodesNode)
            if (episodes.isEmpty()) continue
            seasons += AllohaSeason(
                number = key.toIntOrNull() ?: position,
                episodes = episodes,
            )
        }
        return seasons.sortedBy(AllohaSeason::number)
    }

    private fun parseEpisodes(episodesNode: JSONObject): List<AllohaEpisode> {
        val episodes = mutableListOf<AllohaEpisode>()
        val keys = episodesNode.keys()
        var position = 0
        while (keys.hasNext()) {
            val key = keys.next()
            position++
            val episodeNode = episodesNode.optJSONObject(key) ?: continue
            val translationsNode = episodeNode.optJSONObject("translation") ?: continue
            val translations = parseTranslations(translationsNode)
            if (translations.isEmpty()) continue
            episodes += AllohaEpisode(
                number = key.toIntOrNull() ?: position,
                translations = translations,
            )
        }
        return episodes.sortedBy(AllohaEpisode::number)
    }

    private fun parseTranslations(node: JSONObject): List<AllohaTranslation> {
        val translations = mutableListOf<AllohaTranslation>()
        val keys = node.keys()
        var position = 0
        while (keys.hasNext()) {
            val key = keys.next()
            position++
            val translationNode = node.optJSONObject(key) ?: continue
            val iframeUrl = translationNode.optNonBlank("iframe")?.normalizeUrl() ?: continue
            translations += AllohaTranslation(
                id = key,
                name = translationNode.optNonBlank("translation") ?: "Озвучка $position",
                iframeUrl = iframeUrl,
            )
        }
        return translations
            .distinctBy(AllohaTranslation::iframeUrl)
            .sortedBy { it.name.lowercase() }
    }
}

private fun JSONObject.optNonBlank(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf(String::isNotBlank)

private fun String.normalizeUrl(): String = if (startsWith("//")) "https:$this" else this
