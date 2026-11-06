package org.emilumiq.alloha.parser

import org.json.JSONObject

fun parseHeaders(headersJson: String): Map<String, String> {
    if (headersJson.isBlank()) return emptyMap()
    val node = runCatching { JSONObject(headersJson) }.getOrNull() ?: return emptyMap()
    return buildMap {
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (node.isNull(key)) continue
            val value = node.optString(key)
            if (value.isNotBlank()) put(key.lowercase(), value)
        }
    }
}
