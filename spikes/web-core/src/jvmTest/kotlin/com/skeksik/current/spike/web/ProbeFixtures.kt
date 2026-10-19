package com.skeksik.current.spike.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Raw probe answers recorded on the owner's PC (Windows 11, HDR display with Windows HDR on, stereo output), 2026-10-19:
 * Chrome and Edge through Playwright (headed, without its forced sRGB colour profile), Firefox by hand from the console
 * (`window.__currentProbes`, before MP3 and HEVC got alternate codec spellings).
 */
object ProbeFixtures {
    fun load(name: String): Map<String, String> {
        val text = ProbeFixtures::class.java.getResource("/probes/$name.json")!!.readText()
        return Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    val chrome get() = load("chrome-155.0.8059.85")
    val edge get() = load("edge-155.0.4283.45")
    val firefox get() = load("firefox-158.0")
}

class ProbeFixturesTest {
    private val chrome = BrowserProbes.interpret(ProbeFixtures.chrome)
    private val edge = BrowserProbes.interpret(ProbeFixtures.edge)
    private val firefox = BrowserProbes.interpret(ProbeFixtures.firefox)

    private fun MediaSupport.audioIn(container: String) = direct.first { it.container == container }.audio

    @Test
    fun onlyEdgeDecodesDolbyAudio() {
        assertTrue(edge.audioIn("mp4,m4v").containsAll(listOf("ac3", "eac3")))
        assertEquals("aac", edge.hlsAudio.first())
        assertTrue(edge.hlsAudio.containsAll(listOf("ac3", "eac3")))
        for (s in listOf(chrome, firefox)) assertFalse(s.audioIn("mp4,m4v").any { it == "ac3" || it == "eac3" })
        assertEquals(listOf("aac", "opus", "flac"), firefox.hlsAudio)
    }

    @Test
    fun allThreePlayMkvAndHevcTenBitIn4k() {
        for (s in listOf(chrome, edge, firefox)) {
            assertTrue(s.direct.map { it.container }.containsAll(listOf("mp4,m4v", "webm", "mkv")))
            assertTrue("hevc" in s.direct.first { it.container == "mkv" }.video)
            assertEquals(listOf("h264", "hevc", "av1"), s.hlsVideo)
            assertTrue(s.tenBit.containsAll(listOf("hevc", "av1", "vp9")))
            assertEquals(setOf("h264", "hevc", "av1", "vp9"), s.uhd)
            assertEquals(2, s.maxAudioChannels)
        }
        assertTrue("mov" in firefox.direct.map { it.container })
        assertFalse("flac" in firefox.audioIn("mkv"))
    }

    @Test
    fun hdrPassesThroughOnlyWhereTheBrowserReportsAnHdrDisplay() {
        for (s in listOf(chrome, edge)) assertTrue("HDR10" in s.ranges.getValue("hevc"))
        assertEquals(listOf("SDR", "DOVIWithSDR"), firefox.ranges["hevc"], "Firefox reports no HDR display, so the server tone-maps")
        assertFalse("HLG" in chrome.ranges.getValue("hevc"), "no HLG decoding reported")
    }
}
