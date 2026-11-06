package org.emilumiq.alloha.ui

import org.emilumiq.alloha.ui.components.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {
    @Test
    fun `formats zero and negative durations`() {
        assertEquals("00:00", formatDuration(0L))
        assertEquals("00:00", formatDuration(-5_000L))
    }

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("00:05", formatDuration(5_000L))
        assertEquals("01:05", formatDuration(65_000L))
        assertEquals("59:59", formatDuration(3_599_000L))
    }

    @Test
    fun `formats hours`() {
        assertEquals("1:00:00", formatDuration(3_600_000L))
        assertEquals("1:01:01", formatDuration(3_661_000L))
    }
}
