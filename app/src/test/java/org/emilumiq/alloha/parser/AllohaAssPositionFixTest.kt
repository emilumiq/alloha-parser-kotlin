package org.emilumiq.alloha.parser

import org.junit.Assert.assertTrue
import org.junit.Test

class AllohaAssPositionFixTest {
    @Test
    fun `applies margin based position and adds play resolution`() {
        val fixed = fixAssMarginPositions(
            """
            [Script Info]
            Title: test

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,1,2,10,10,40,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Привет
            """.trimIndent()
        )

        assertTrue(fixed.contains("{\\pos(500,960)}Привет"))
        assertTrue(fixed.contains("PlayResX: 1000"))
        assertTrue(fixed.contains("PlayResY: 1000"))
    }

    @Test
    fun `keeps explicit positioning untouched`() {
        val fixed = fixAssMarginPositions(
            """
            [Script Info]
            PlayResX: 1920
            PlayResY: 1080

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,1,8,10,10,30,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\pos(10,20)}Верх
            """.trimIndent()
        )

        assertTrue(fixed.contains("{\\pos(10,20)}Верх"))
    }

    @Test
    fun `uses style alignment for top anchored subtitles`() {
        val fixed = fixAssMarginPositions(
            """
            [Script Info]
            PlayResX: 1920
            PlayResY: 1080

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Top,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,1,1,8,10,10,30,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Top,,0,0,0,,Верх
            """.trimIndent()
        )

        assertTrue(fixed.contains("{\\pos(960,30)}Верх"))
    }
}
