package org.emilumiq.alloha.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSnapshotCodecTest {
    @Test
    fun `round trips a snapshot`() {
        val snapshot = PlaybackSnapshot(
            title = "Дом Дракона",
            episodeLabel = "S2 E10 · LostFilm",
            iframeUrl = "https://alloha/iframe",
            audioId = "10",
            audioLabel = "LostFilm",
            qualityLabel = "1080p",
            positionMs = 123_456L,
            durationMs = 3_600_000L,
            wasPlaying = true,
            updatedAtMs = 1_700_000_000_000L,
        )

        assertEquals(snapshot, PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `keeps empty optional fields null`() {
        val snapshot = PlaybackSnapshot(
            title = "Фильм",
            episodeLabel = null,
            iframeUrl = "https://alloha/iframe",
            audioId = null,
            audioLabel = null,
            qualityLabel = null,
            positionMs = 0L,
            durationMs = 0L,
            wasPlaying = false,
            updatedAtMs = 1L,
        )

        val decoded = PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `rejects broken or unusable payloads`() {
        assertNull(PlaybackSnapshotCodec.decode("broken"))
        assertNull(PlaybackSnapshotCodec.decode("""{"title":"x","iframeUrl":""}"""))
    }

    @Test
    fun `encodes fields used by the resume card`() {
        val encoded = PlaybackSnapshotCodec.encode(
            PlaybackSnapshot(
                title = "Фильм",
                episodeLabel = null,
                iframeUrl = "https://alloha/iframe",
                audioId = null,
                audioLabel = null,
                qualityLabel = null,
                positionMs = 42L,
                durationMs = 0L,
                wasPlaying = false,
                updatedAtMs = 7L,
            )
        )

        assertTrue(encoded.contains("\"positionMs\":42"))
        assertTrue(encoded.contains("\"iframeUrl\":\"https://alloha/iframe\""))
    }
}
