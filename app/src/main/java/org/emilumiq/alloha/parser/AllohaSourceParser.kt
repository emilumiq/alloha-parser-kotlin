package org.emilumiq.alloha.parser

import org.json.JSONArray
import org.json.JSONObject

object AllohaSourceParser {
    fun parse(responseJson: String): AllohaParsedSources {
        val root = JSONObject(responseJson)
        val sourcesNode = root.optJSONArray("hlsSource")
            ?: throw AllohaSourceUnavailableException("hlsSource отсутствует")

        val audioTracks = parseAudioTracks(sourcesNode)
        if (audioTracks.isEmpty()) throw AllohaSourceUnavailableException("нет доступных качеств")

        return AllohaParsedSources(
            audioTracks = audioTracks,
            subtitles = parseSubtitles(root),
        )
    }

    private fun parseAudioTracks(sourcesNode: JSONArray): List<AllohaParsedAudioTrack> =
        buildList {
            for (index in 0 until sourcesNode.length()) {
                val sourceNode = sourcesNode.optJSONObject(index) ?: continue
                val qualityNode = sourceNode.optJSONObject("quality") ?: continue
                val qualities = parseQualities(qualityNode)
                if (qualities.isEmpty()) continue
                add(
                    AllohaParsedAudioTrack(
                        track = AllohaAudioTrack(
                            id = sourceNode.optNonBlank("audioId") ?: index.toString(),
                            label = sourceNode.optNonBlank("label") ?: "Озвучка ${index + 1}",
                            isDefault = sourceNode.optBoolean("default"),
                        ),
                        qualities = qualities,
                    )
                )
            }
        }

    private fun parseQualities(qualityNode: JSONObject): LinkedHashMap<String, String> {
        val qualities = linkedMapOf<String, String>()
        val keys = qualityNode.keys()
        while (keys.hasNext()) {
            val label = keys.next()
            if (qualityNode.isNull(label)) continue
            val primaryUrl = qualityNode.optString(label)
                .split(PRIMARY_URL_SEPARATOR)
                .firstOrNull()
                ?.trim()
                ?.normalizeStreamUrl()
                ?.takeIf(String::isNotBlank)
                ?: continue
            qualities.putIfAbsent(label.toQualityLabel(), primaryUrl)
        }
        return qualities.entries
            .sortedBy { it.key.filter(Char::isDigit).toIntOrNull() ?: 0 }
            .associateTo(linkedMapOf()) { it.toPair() }
    }

    private fun parseSubtitles(root: JSONObject): List<AllohaSubtitleTrack> = buildList {
        val tracksNode = root.optJSONArray("tracks") ?: return@buildList
        for (index in 0 until tracksNode.length()) {
            val trackNode = tracksNode.optJSONObject(index) ?: continue
            if (!trackNode.optString("kind").equals("captions", ignoreCase = true)) continue
            val src = trackNode.optNonBlank("src")?.normalizeStreamUrl() ?: continue
            add(
                AllohaSubtitleTrack(
                    label = trackNode.optNonBlank("label") ?: "Субтитры ${index + 1}",
                    url = src,
                    language = trackNode.optNonBlank("language"),
                    format = src.substringBefore('?').substringAfterLast('.', "").lowercase()
                        .takeIf(String::isNotBlank),
                )
            )
        }
    }

    private val PRIMARY_URL_SEPARATOR = Regex("""\s+or\s+""", RegexOption.IGNORE_CASE)
}

internal fun String.normalizeStreamUrl(): String = if (startsWith("//")) "https:$this" else this

internal fun String.toQualityLabel(): String {
    val clean = trim()
    if (clean.endsWith("p", ignoreCase = true)) return clean
    return if (clean.isNotEmpty() && clean.all(Char::isDigit)) "${clean}p" else clean
}

private fun JSONObject.optNonBlank(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf(String::isNotBlank)
