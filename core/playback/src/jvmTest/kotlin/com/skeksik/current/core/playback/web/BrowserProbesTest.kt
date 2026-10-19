package com.skeksik.current.core.playback.web

import com.skeksik.current.core.playback.DeviceProfile
import com.skeksik.current.core.playback.ProfileCondition.Property
import com.skeksik.current.core.playback.SubtitleMethod
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The raw probe answers in `docs/web-probes/`, recorded on 2026-10-19 (ADR 0011):
 * - Chrome, Edge and Firefox on the owner's PC (Windows 11, HDR display with Windows HDR on, stereo output). Chrome and Edge through
 *   Playwright (headed, without its forced sRGB colour profile); Firefox by hand, before MP3 and HEVC got their other codec spellings.
 * - Safari on the owner's Mac, by hand; its display reported no HDR.
 *
 * None of them has the Dolby Vision probes yet.
 */
private object Fixtures {
    private val dir = File(System.getProperty("current.webProbes") ?: error("current.webProbes is set by the Gradle test task"))

    fun text(name: String) = dir.resolve("$name.json").readText()

    /** The files are flat JSON objects of strings, one entry per line. */
    fun answers(name: String): Map<String, String> = Regex(""""((?:[^"\\]|\\.)*)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        .findAll(text(name))
        .associate { it.groupValues[1] to it.groupValues[2] }

    const val CHROME = "chrome-155.0.8059.85"
    const val EDGE = "edge-155.0.4283.45"
    const val FIREFOX = "firefox-158.0"
    const val SAFARI = "safari-macos-2026-10-19"
    val all = listOf(CHROME, EDGE, FIREFOX, SAFARI)
}

class BrowserProbesTest {
    private val chrome = BrowserProbes.interpret(Fixtures.answers(Fixtures.CHROME))
    private val edge = BrowserProbes.interpret(Fixtures.answers(Fixtures.EDGE))
    private val firefox = BrowserProbes.interpret(Fixtures.answers(Fixtures.FIREFOX))
    private val safari = BrowserProbes.interpret(Fixtures.answers(Fixtures.SAFARI))

    private fun MediaSupport.containers() = direct.map { it.container }

    private fun MediaSupport.videoIn(container: String) = direct.first { it.container == container }.video

    private fun MediaSupport.audioIn(container: String) = direct.first { it.container == container }.audio

    @Test
    fun fixturesAnswerOnlyQueriesOfTheTableAndRoundTrip() {
        val ids = BrowserProbes.queries.map { it.id }.toSet()
        for (name in Fixtures.all) {
            val answers = Fixtures.answers(name)
            assertTrue(answers.size > 80, name)
            assertEquals(emptySet(), answers.keys - ids, "$name answers queries the table no longer asks")
            assertEquals(Fixtures.text(name), BrowserProbes.interpret(answers).answersJson(), "$name in the recording format")
        }
    }

    @Test
    fun queryIdsAreUnique() {
        val ids = BrowserProbes.queries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun onlyEdgeAndSafariDecodeDolbyAudio() {
        for (s in listOf(edge, safari)) {
            assertTrue(s.audioIn("mp4,m4v").containsAll(listOf("ac3", "eac3")))
            assertEquals("aac", s.hlsAudio.first())
            assertTrue(s.hlsAudio.containsAll(listOf("ac3", "eac3")))
        }
        for (s in listOf(chrome, firefox)) assertFalse(s.audioIn("mp4,m4v").any { it == "ac3" || it == "eac3" })
        assertEquals(listOf("aac", "opus", "flac"), firefox.hlsAudio)
    }

    @Test
    fun chromeEdgeAndFirefoxPlayMkvAndHevcTenBitIn4k() {
        for (s in listOf(chrome, edge, firefox)) {
            assertTrue(s.containers().containsAll(listOf("mp4,m4v", "webm", "mkv")))
            assertTrue("hevc" in s.videoIn("mkv"))
            assertEquals(listOf("h264", "hevc", "av1"), s.hlsVideo)
            assertTrue(s.tenBit.containsAll(listOf("hevc", "av1", "vp9")))
            assertEquals(setOf("h264", "hevc", "av1", "vp9"), s.uhd)
            assertEquals(2, s.maxAudioChannels)
            assertTrue(s.mse)
        }
        // Chrome and Edge also answer "maybe" for native HLS; it only matters where there is no MSE.
        assertTrue(chrome.nativeHls && edge.nativeHls)
        assertFalse(firefox.nativeHls)
        assertTrue("mov" in firefox.containers())
        assertFalse("flac" in firefox.audioIn("mkv"))
        assertFalse("h264" in firefox.tenBit, "Firefox has no 10-bit H.264")
    }

    @Test
    fun safariHasNoMkvNoAv1AndNativeHls() {
        assertEquals(listOf("mp4,m4v", "webm", "mov", "ts,mpegts"), safari.containers())
        assertEquals(listOf("h264", "hevc", "vp9"), safari.videoIn("mp4,m4v"))
        assertEquals(listOf("vp8", "vp9"), safari.videoIn("webm"))
        assertEquals(listOf("aac", "ac3", "eac3", "flac", "alac"), safari.audioIn("mp4,m4v"))
        assertEquals(listOf("opus", "vorbis"), safari.audioIn("webm"))
        assertEquals(listOf("ac3", "eac3"), safari.audioIn("ts,mpegts"))
        assertEquals(listOf("h264", "hevc"), safari.hlsVideo)
        assertEquals(listOf("aac", "ac3", "eac3", "flac", "alac"), safari.hlsAudio)
        assertEquals(setOf("h264", "hevc", "vp9"), safari.uhd)
        assertTrue(safari.tenBit.containsAll(listOf("h264", "hevc", "vp9")))
        assertTrue(safari.nativeHls)
    }

    @Test
    fun hdrPassesThroughOnlyWhereTheBrowserReportsAnHdrDisplay() {
        for (s in listOf(chrome, edge)) {
            assertTrue(s.hdrDisplay)
            assertTrue("HDR10" in s.ranges.getValue("hevc"))
            assertFalse("HDR10" in s.ranges.getValue("av1"), "decodingInfo does not report AV1 HDR")
            assertFalse("HLG" in s.ranges.getValue("hevc"), "no HLG decoding reported")
            assertEquals(listOf("SDR", "PQ"), s.hlsVideoRanges)
            assertTrue(s.preferHdr)
        }
        // Firefox and Safari decode HDR10 and HLG in HEVC, but their displays reported no HDR, so the server tone-maps.
        for (s in listOf(firefox, safari)) {
            assertFalse(s.hdrDisplay)
            assertEquals(listOf("SDR", "DOVIWithSDR"), s.ranges["hevc"])
            assertEquals(listOf("SDR"), s.hlsVideoRanges)
            assertFalse(s.preferHdr)
        }
    }

    @Test
    fun noFixtureDecodesDolbyVisionItself() {
        for (s in listOf(chrome, edge, firefox, safari)) {
            assertFalse("DOVI" in s.ranges.getValue("hevc"))
            assertFalse("DOVIWithHDR10Plus" in s.ranges.getValue("hevc"))
        }
    }

    @Test
    fun dolbyVisionPassesOnlyOnAnHdrDisplay() {
        val dolbyVision = mapOf("dovi5:hevc" to "smooth", "dovi8:hevc" to "false", "dovi8:hevc#1" to "smooth")
        // Safari's answers, as if its display were HDR and it decoded Dolby Vision (expected on a Mac with an HDR display; not recorded).
        val hdr = BrowserProbes.interpret(Fixtures.answers(Fixtures.SAFARI) + dolbyVision + ("display:hdr" to "true"))
        val hevc = hdr.ranges.getValue("hevc")
        val dolby = listOf("DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithHDR10Plus")
        assertTrue(hevc.containsAll(listOf("SDR", "HDR10", "HLG") + dolby))
        assertEquals(hevc.size, hevc.toSet().size)
        assertFalse("DOVI" in hdr.ranges.getValue("h264"))
        assertEquals(listOf("SDR", "PQ", "HLG"), hdr.hlsVideoRanges)

        val sdr = BrowserProbes.interpret(Fixtures.answers(Fixtures.SAFARI) + dolbyVision)
        assertEquals(listOf("SDR", "DOVIWithSDR"), sdr.ranges["hevc"])

        // Profile 5 alone: the stream has no base layer, so PQ is still what hls.js must prefer.
        val doviOnly = BrowserProbes.interpret(mapOf("display:hdr" to "true", "dovi5:hevc" to "supported"))
        assertEquals(listOf("SDR", "DOVIWithSDR", "DOVI"), doviOnly.ranges["hevc"])
        assertEquals(listOf("SDR", "PQ"), doviOnly.hlsVideoRanges)
    }

    @Test
    fun nothingAnsweredMeansH264AndAacThroughTheServer() {
        val none = BrowserProbes.interpret(emptyMap())
        assertEquals(emptyList(), none.direct)
        assertEquals(listOf("h264"), none.hlsVideo)
        assertEquals(listOf("aac"), none.hlsAudio)
        assertEquals(2, none.maxAudioChannels)
    }
}

class WebDeviceProfileTest {
    private fun profile(name: String) = WebDeviceProfile.build(BrowserProbes.interpret(Fixtures.answers(name)))

    private fun DeviceProfile.condition(codec: String, property: Property) =
        codecs.first { it.codec == codec }.conditions.first { it.property == property }.value

    @Test
    fun chromeCopiesHdrHevcIn4k() {
        val chrome = profile(Fixtures.CHROME)
        assertEquals(listOf("h264", "hevc", "av1"), chrome.transcoding.single().videoCodecs)
        assertEquals("aac", chrome.transcoding.single().audioCodecs.first())
        assertEquals(2, chrome.transcoding.single().maxAudioChannels)
        assertEquals("SDR|DOVIWithSDR|HDR10|HDR10Plus|DOVIWithHDR10", chrome.condition("hevc", Property.VideoRangeType))
        assertEquals("10", chrome.condition("hevc", Property.VideoBitDepth))
        assertEquals("3840", chrome.condition("hevc", Property.Width))
        assertEquals("2160", chrome.condition("hevc", Property.Height))
    }

    @Test
    fun firefoxToneMapsAndSendsTenBitH264ToTheServer() {
        val firefox = profile(Fixtures.FIREFOX)
        assertEquals("SDR|DOVIWithSDR", firefox.condition("hevc", Property.VideoRangeType))
        assertEquals("8", firefox.condition("h264", Property.VideoBitDepth))
    }

    @Test
    fun safariPlaysMovAndTsDirectlyAndHasACodecProfileForEveryCodec() {
        val safari = profile(Fixtures.SAFARI)
        assertEquals(listOf("mp4,m4v", "webm", "mov", "ts,mpegts"), safari.directPlay.map { it.container })
        assertEquals(listOf("h264", "hevc", "vp9", "vp8"), safari.codecs.map { it.codec })
        assertEquals("1920", safari.condition("vp8", Property.Width))
    }

    @Test
    fun textSubtitlesAsWebVttOthersBurnedIn() {
        val subtitles = profile(Fixtures.EDGE).subtitles.associate { it.format to it.method }
        assertEquals(SubtitleMethod.External, subtitles["vtt"])
        for (format in listOf("ass", "ssa", "pgssub", "dvdsub", "dvbsub")) assertEquals(SubtitleMethod.Encode, subtitles[format])
    }
}
