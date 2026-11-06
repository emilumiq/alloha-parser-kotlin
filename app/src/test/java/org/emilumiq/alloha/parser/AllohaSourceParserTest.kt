package org.emilumiq.alloha.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AllohaSourceParserTest {
    @Test
    fun `parses audio tracks with normalized sorted qualities`() {
        val sources = AllohaSourceParser.parse(
            """
            {
              "hlsSource": [
                {
                  "audioId": "10",
                  "label": "(Russian) AniLibria",
                  "default": true,
                  "quality": {
                    "720": "https://a/720/master.m3u8 or https://b/720/master.m3u8",
                    "1080": "//a/1080/master.m3u8"
                  }
                },
                {
                  "label": "LostFilm",
                  "quality": { "480": "https://c/480/master.m3u8" }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, sources.audioTracks.size)
        val russian = sources.audioTracks[0]
        assertEquals("10", russian.track.id)
        assertEquals("(Russian) AniLibria", russian.track.label)
        assertTrue(russian.track.isDefault)
        assertEquals(listOf("720p", "1080p"), russian.qualities.keys.toList())
        assertEquals("https://a/720/master.m3u8", russian.qualities["720p"])
        assertEquals("https://a/1080/master.m3u8", russian.qualities["1080p"])

        val lostFilm = sources.audioTracks[1]
        assertEquals("1", lostFilm.track.id)
        assertFalse(lostFilm.track.isDefault)
    }

    @Test
    fun `parses caption subtitles`() {
        val sources = AllohaSourceParser.parse(
            """
            {
              "hlsSource": [ { "quality": { "720": "https://a/master.m3u8" } } ],
              "tracks": [
                { "kind": "captions", "label": "Русский", "language": "rus", "src": "//a/sub.vtt" },
                { "kind": "thumbnails", "label": "Превью", "src": "//a/thumbs.vtt" }
              ]
            }
            """.trimIndent()
        )

        val subtitle = sources.subtitles.single()
        assertEquals("Русский", subtitle.label)
        assertEquals("https://a/sub.vtt", subtitle.url)
        assertEquals("rus", subtitle.language)
        assertEquals("vtt", subtitle.format)
    }

    @Test
    fun `skips audio tracks without qualities`() {
        val sources = AllohaSourceParser.parse(
            """
            {
              "hlsSource": [
                { "label": "Пусто", "quality": {} },
                { "label": "Рабочая", "quality": { "1080": "https://a/master.m3u8" } }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, sources.audioTracks.size)
        assertEquals("Рабочая", sources.audioTracks.single().track.label)
    }

    @Test
    fun `fails without hls source`() {
        assertThrows(AllohaSourceUnavailableException::class.java) {
            AllohaSourceParser.parse("""{"tracks":[]}""")
        }
    }

    @Test
    fun `fails when no audio track has qualities`() {
        assertThrows(AllohaSourceUnavailableException::class.java) {
            AllohaSourceParser.parse("""{"hlsSource":[{"quality":{}}]}""")
        }
    }

    @Test
    fun `normalizes protocol relative urls`() {
        assertEquals("https://cdn/x", "//cdn/x".normalizeStreamUrl())
        assertEquals("https://cdn/x", "https://cdn/x".normalizeStreamUrl())
    }

    @Test
    fun `normalizes quality labels`() {
        assertEquals("1080p", "1080".toQualityLabel())
        assertEquals("1080p", "1080p".toQualityLabel())
        assertEquals("авто", "авто".toQualityLabel())
    }

    @Test
    fun `parses headers into lowercase map`() {
        val headers = parseHeaders(
            """{"Authorizations":"abc"," accepts-controls ":"edge","Empty":"","nulled":null}"""
        )

        assertEquals("abc", headers["authorizations"])
        assertNull(headers["empty"])
        assertNull(headers["nulled"])
    }

    @Test
    fun `returns empty map for blank headers`() {
        assertTrue(parseHeaders("").isEmpty())
        assertTrue(parseHeaders("broken").isEmpty())
    }
}
