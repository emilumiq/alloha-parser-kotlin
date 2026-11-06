package org.emilumiq.alloha.proxy

import org.emilumiq.alloha.parser.fixAssMarginPositions
import java.net.URLDecoder

internal object AllohaProxyUrls {
    fun isPlaylist(url: String): Boolean = url.contains(".m3u8", ignoreCase = true)

    fun isHttp(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    fun contentType(url: String): String = when {
        url.contains(".vtt", ignoreCase = true) -> "text/vtt"
        url.contains(".ass", ignoreCase = true) || url.contains(".ssa", ignoreCase = true) -> "text/x-ssa"
        url.contains(".aac", ignoreCase = true) -> "audio/aac"
        url.contains(".m4s", ignoreCase = true) || url.contains(".mp4", ignoreCase = true) -> "video/mp4"
        else -> "video/MP2T"
    }

    fun parseQuery(path: String): Map<String, String> {
        val query = path.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return buildMap {
            query.splitToSequence('&').forEach { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@forEach
                val value = runCatching {
                    URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
                }.getOrNull() ?: return@forEach
                put(pair.substring(0, separator), value)
            }
        }
    }

    fun isAssSubtitle(url: String): Boolean {
        val extension = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return extension == "ass" || extension == "ssa"
    }

    fun fixAssBytes(bytes: ByteArray): ByteArray = runCatching {
        fixAssMarginPositions(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
    }.getOrDefault(bytes)

    fun rewriteToCurrentPath(failedUrl: String, currentMaster: String): String? {
        if (currentMaster.isBlank()) return null
        val segmentName = failedUrl.substringAfterLast('/').substringBefore('?')
        if (segmentName.isBlank() || segmentName.equals("master.m3u8", ignoreCase = true)) return null
        val newBase = currentMaster.substringBeforeLast('/') + "/"
        val oldBase = failedUrl.substringBeforeLast('/') + "/"
        if (oldBase == newBase) return null
        return newBase + segmentName
    }
}
