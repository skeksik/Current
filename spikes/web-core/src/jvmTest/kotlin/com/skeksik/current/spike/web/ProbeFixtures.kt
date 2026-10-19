package com.skeksik.current.spike.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Raw probe answers recorded in real browsers (Windows, headed, hardware decoding; display without HDR, stereo output). */
object ProbeFixtures {
    fun load(name: String): Map<String, String> {
        val text = ProbeFixtures::class.java.getResource("/probes/$name.json")!!.readText()
        return Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    val chrome get() = load("chrome-155.0.8059.85")
    val edge get() = load("edge-155.0.4283.45")

    /** Not recorded (Playwright cannot drive a stock Firefox): Chrome's answers without MKV and HEVC, as Firefox is described. */
    val firefoxLike get() = chrome.mapValues { (k, v) -> if (k.startsWith("play:mkv:") || "hevc" in k) "" else v }
}

class ProbeFixturesTest {
    @Test
    fun edgeDirectPlaysAndCopiesDolbyAudioChromeDoesNot() {
        val edge = BrowserProbes.interpret(ProbeFixtures.edge)
        val chrome = BrowserProbes.interpret(ProbeFixtures.chrome)
        val mp4Audio = { s: MediaSupport -> s.direct.first { it.container == "mp4,m4v" }.audio }
        assertTrue(mp4Audio(edge).containsAll(listOf("ac3", "eac3")))
        assertFalse("ac3" in mp4Audio(chrome) || "eac3" in mp4Audio(chrome))
        assertEquals(listOf("aac", "ac3", "eac3", "opus", "flac"), edge.hlsAudio)
        assertEquals(listOf("aac", "opus", "flac"), chrome.hlsAudio)
    }

    @Test
    fun bothPlayMkvHevcTenBitAnd4kButNoHdrOnThisDisplay() {
        for (s in listOf(BrowserProbes.interpret(ProbeFixtures.chrome), BrowserProbes.interpret(ProbeFixtures.edge))) {
            assertEquals(listOf("mp4,m4v", "webm", "mkv"), s.direct.map { it.container })
            assertEquals(listOf("h264", "hevc", "av1"), s.hlsVideo)
            assertTrue(s.tenBit.containsAll(listOf("hevc", "av1", "vp9")))
            assertEquals(setOf("h264", "hevc", "av1", "vp9"), s.uhd)
            assertEquals(listOf("SDR", "DOVIWithSDR"), s.ranges["hevc"], "the display reported no HDR")
            assertEquals(2, s.maxAudioChannels)
        }
    }
}
