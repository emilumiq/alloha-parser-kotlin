package org.emilumiq.alloha.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllohaProxyUrlsTest {
    @Test
    fun `parses and decodes query parameters`() {
        val params = AllohaProxyUrls.parseQuery("/proxy?url=aHR0cHM6Ly9jZG4%3D&token=abc&broken")

        assertEquals("aHR0cHM6Ly9jZG4=", params["url"])
        assertEquals("abc", params["token"])
        assertFalse(params.containsKey("broken"))
    }

    @Test
    fun `returns empty query for path without parameters`() {
        assertTrue(AllohaProxyUrls.parseQuery("/master.m3u8").isEmpty())
    }

    @Test
    fun `rewrites stale segment path to the current session path`() {
        val rewritten = AllohaProxyUrls.rewriteToCurrentPath(
            failedUrl = "https://cdn/56/OLD/seg-26.ts",
            currentMaster = "https://cdn/56/NEW/master.m3u8",
        )

        assertEquals("https://cdn/56/NEW/seg-26.ts", rewritten)
    }

    @Test
    fun `keeps query string out of the rewritten segment name`() {
        val rewritten = AllohaProxyUrls.rewriteToCurrentPath(
            failedUrl = "https://cdn/56/OLD/seg-26.ts?sig=1",
            currentMaster = "https://cdn/56/NEW/master.m3u8",
        )

        assertEquals("https://cdn/56/NEW/seg-26.ts", rewritten)
    }

    @Test
    fun `does not rewrite when path already matches`() {
        assertNull(
            AllohaProxyUrls.rewriteToCurrentPath(
                failedUrl = "https://cdn/56/NEW/seg-1.ts",
                currentMaster = "https://cdn/56/NEW/master.m3u8",
            )
        )
    }

    @Test
    fun `does not rewrite playlist or blank master`() {
        assertNull(
            AllohaProxyUrls.rewriteToCurrentPath(
                failedUrl = "https://cdn/56/NEW/master.m3u8",
                currentMaster = "https://cdn/56/NEW/master.m3u8",
            )
        )
        assertNull(AllohaProxyUrls.rewriteToCurrentPath("https://cdn/a.ts", ""))
    }

    @Test
    fun `detects playlist and http urls`() {
        assertTrue(AllohaProxyUrls.isPlaylist("https://cdn/master.m3u8?x=1"))
        assertTrue(AllohaProxyUrls.isHttp("https://cdn"))
        assertTrue(AllohaProxyUrls.isHttp("http://127.0.0.1:1/x"))
        assertFalse(AllohaProxyUrls.isHttp("ftp://cdn"))
    }

    @Test
    fun `maps content types`() {
        assertEquals("text/vtt", AllohaProxyUrls.contentType("https://cdn/sub.vtt"))
        assertEquals("text/x-ssa", AllohaProxyUrls.contentType("https://cdn/sub.ASS"))
        assertEquals("audio/aac", AllohaProxyUrls.contentType("https://cdn/audio.aac"))
        assertEquals("video/mp4", AllohaProxyUrls.contentType("https://cdn/seg.m4s"))
        assertEquals("video/MP2T", AllohaProxyUrls.contentType("https://cdn/seg.ts"))
    }

    @Test
    fun `detects ass subtitles`() {
        assertTrue(AllohaProxyUrls.isAssSubtitle("https://cdn/sub.ass?x=1"))
        assertTrue(AllohaProxyUrls.isAssSubtitle("https://cdn/sub.ssa"))
        assertFalse(AllohaProxyUrls.isAssSubtitle("https://cdn/sub.vtt"))
    }
}
