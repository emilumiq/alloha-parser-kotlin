package org.emilumiq.alloha.player

import org.json.JSONObject

object PlaybackSnapshotCodec {
    fun encode(snapshot: PlaybackSnapshot): String = JSONObject().apply {
        put("title", snapshot.title)
        put("episodeLabel", snapshot.episodeLabel)
        put("iframeUrl", snapshot.iframeUrl)
        put("audioId", snapshot.audioId)
        put("audioLabel", snapshot.audioLabel)
        put("qualityLabel", snapshot.qualityLabel)
        put("positionMs", snapshot.positionMs)
        put("durationMs", snapshot.durationMs)
        put("wasPlaying", snapshot.wasPlaying)
        put("updatedAtMs", snapshot.updatedAtMs)
    }.toString()

    fun decode(raw: String): PlaybackSnapshot? = runCatching {
        val json = JSONObject(raw)
        PlaybackSnapshot(
            title = json.optString("title"),
            episodeLabel = json.optNullableString("episodeLabel"),
            iframeUrl = json.optString("iframeUrl"),
            audioId = json.optNullableString("audioId"),
            audioLabel = json.optNullableString("audioLabel"),
            qualityLabel = json.optNullableString("qualityLabel"),
            positionMs = json.optLong("positionMs"),
            durationMs = json.optLong("durationMs"),
            wasPlaying = json.optBoolean("wasPlaying"),
            updatedAtMs = json.optLong("updatedAtMs"),
        ).takeIf { it.iframeUrl.isNotBlank() }
    }.getOrNull()

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
}
