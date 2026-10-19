package com.skeksik.current.spike.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTest {
    @Test
    fun parsesEveryPageAndFormatsItBack() {
        val routes = listOf(Route.Home, Route.SignIn, Route.Library(ITEM_ID), Route.Item(ITEM_ID), Route.Player(ITEM_ID))
        routes.forEach { assertEquals(it, Route.parse(it.path)) }
    }

    @Test
    fun ignoresQueryFragmentAndTrailingSlash() {
        assertEquals(Route.Item(ITEM_ID), Route.parse("/items/$ITEM_ID/?x=1#top"))
    }

    @Test
    fun unknownPathsAndBadIdsAreNotFound() {
        assertEquals(Route.NotFound("/items/xyz"), Route.parse("/items/xyz"))
        assertEquals(Route.NotFound("/nope"), Route.parse("/nope"))
    }

    @Test
    fun onlyPlainPrimaryClicksAreHandledByTheApp() {
        assertTrue(isPlainClick(0, ctrl = false, meta = false, shift = false, alt = false))
        assertFalse(isPlainClick(1, ctrl = false, meta = false, shift = false, alt = false))
        assertFalse(isPlainClick(0, ctrl = true, meta = false, shift = false, alt = false))
        assertFalse(isPlainClick(0, ctrl = false, meta = true, shift = false, alt = false))
        assertFalse(isPlainClick(0, ctrl = false, meta = false, shift = true, alt = false))
    }

    @Test
    fun formatsTimes() {
        assertEquals("0:00", formatTime(Double.NaN))
        assertEquals("2:03", formatTime(123.9))
        assertEquals("1:02:03", formatTime(3723.0))
    }
}

class AuthorizationHeaderTest {
    @Test
    fun quotesAndEncodesValuesAndAddsTheTokenOnlyWhenSignedIn() {
        val info = ClientInfo(deviceId = "abc", device = "Chrome", client = "Current web spike", version = "0.0.1")
        assertEquals(
            "MediaBrowser Client=\"Current%20web%20spike\", Device=\"Chrome\", DeviceId=\"abc\", Version=\"0.0.1\"",
            authorizationHeader(info, null),
        )
        assertTrue(authorizationHeader(info, "t0k").endsWith(", Token=\"t0k\""))
    }

    @Test
    fun normalizesServerUrls() {
        assertEquals("https://jf.example.org", normalizeServerUrl(" jf.example.org/ "))
        assertEquals("http://10.0.0.1:8096", normalizeServerUrl("http://10.0.0.1:8096"))
    }
}

class BrowserProbesTest {
    /** Answers as a browser gives them: every query unanswered ("") except the ones listed. */
    private fun answers(vararg yes: Pair<String, String>) = BrowserProbes.queries.associate { it.id to "" } + mapOf("channels" to "2") + yes

    private fun play(container: String, vararg codecs: String) = codecs.map { "play:$container:$it" to "probably" }.toTypedArray()

    @Test
    fun everyQueryHasAUniqueId() {
        assertEquals(BrowserProbes.queries.size, BrowserProbes.queries.map { it.id }.toSet().size)
    }

    @Test
    fun containersAndCodecsComeOnlyFromProbes() {
        val s = BrowserProbes.interpret(answers(*play("mp4,m4v", "h264", "aac", "ac3"), "mse:h264" to "true"))
        assertEquals(listOf(ContainerSupport("mp4,m4v", listOf("h264"), listOf("aac", "ac3"))), s.direct)
        val withMkv = BrowserProbes.interpret(answers(*play("mp4,m4v", "h264"), *play("mkv", "h264", "opus")))
        assertEquals(listOf("mp4,m4v", "mkv"), withMkv.direct.map { it.container })
    }

    @Test
    fun tenBitProbesCountAsTheirCodec() {
        val s = BrowserProbes.interpret(answers(*play("mp4,m4v", "hevc10"), "mse:hevc10" to "true", "mse:h264" to "true"))
        assertEquals(listOf("hevc"), s.direct.single().video)
        assertTrue("hevc" in s.tenBit)
        assertEquals(listOf("h264", "hevc"), s.hlsVideo)
    }

    @Test
    fun hlsCopiesWhatMseTakesAndEncodesToH264AndAac() {
        val s = BrowserProbes.interpret(answers("mse:h264" to "true", "mse:av1" to "true", "mse:eac3" to "true", "mse:truehd" to "true", "mse:dts" to "true"))
        assertEquals(listOf("h264", "av1"), s.hlsVideo)
        assertEquals(listOf("aac", "eac3"), s.hlsAudio, "AAC first; TrueHD and DTS never go into HLS")
    }

    @Test
    fun withoutMseNativeHlsUsesThePlayProbes() {
        val s = BrowserProbes.interpret(answers("hls" to "maybe", *play("mp4,m4v", "h264", "hevc", "ac3")))
        assertTrue(s.nativeHls)
        assertEquals(listOf("h264", "hevc"), s.hlsVideo)
        assertEquals(listOf("aac", "ac3"), s.hlsAudio)
    }

    @Test
    fun hdrNeedsADecoderAndAnHdrDisplay() {
        val decoder = arrayOf("hdr10:hevc10" to "smooth", "hlg:hevc10" to "supported")
        assertEquals(listOf("SDR", "DOVIWithSDR"), BrowserProbes.interpret(answers(*decoder, "display:hdr" to "false")).ranges["hevc"])
        assertEquals(
            listOf("SDR", "DOVIWithSDR", "HDR10", "HDR10Plus", "DOVIWithHDR10", "HLG", "DOVIWithHLG"),
            BrowserProbes.interpret(answers(*decoder, "display:hdr" to "true")).ranges["hevc"],
        )
        assertEquals(listOf("SDR", "DOVIWithSDR"), BrowserProbes.interpret(answers("display:hdr" to "true")).ranges["hevc"])
    }

    @Test
    fun hlsPrefersHdrVariantsOnlyWhereHdrIsShown() {
        assertEquals(listOf("SDR"), BrowserProbes.interpret(answers("hdr10:hevc10" to "smooth")).hlsVideoRanges)
        assertEquals(listOf("SDR", "PQ"), BrowserProbes.interpret(answers("hdr10:hevc10" to "smooth", "display:hdr" to "true")).hlsVideoRanges)
    }

    @Test
    fun resolutionAndChannelsFromProbes() {
        val s = BrowserProbes.interpret(answers("uhd:hevc" to "smooth", "uhd:av1" to "false", "channels" to "6"))
        assertEquals(setOf("hevc"), s.uhd)
        assertEquals(6, s.maxAudioChannels)
        assertEquals(2, BrowserProbes.interpret(answers("channels" to "1")).maxAudioChannels)
        assertEquals(8, BrowserProbes.interpret(answers("channels" to "32")).maxAudioChannels)
    }
}

class WebDeviceProfileTest {
    private val rich = allSupport.copy(
        direct = allSupport.direct + ContainerSupport("mkv", listOf("h264", "hevc"), listOf("aac", "eac3")),
        hlsVideo = listOf("h264", "hevc"),
        hlsAudio = listOf("aac", "eac3"),
        tenBit = setOf("hevc"),
        ranges = mapOf("hevc" to listOf("SDR", "HDR10")),
        uhd = setOf("hevc"),
        maxAudioChannels = 6,
    )

    @Test
    fun directPlayIsExactlyTheProbedContainers() {
        val profile = WebDeviceProfile.build(rich)
        assertEquals(listOf("mp4,m4v", "webm", "mkv"), profile.directPlayProfiles.map { it.container })
        assertEquals("h264,hevc" to "aac,eac3", profile.directPlayProfiles.last().let { it.videoCodec to it.audioCodec })
    }

    @Test
    fun hlsIsFmp4WithTheProbedCodecsAndChannels() {
        val hls = WebDeviceProfile.build(rich).transcodingProfiles.single()
        assertEquals(listOf("hls", "mp4", "h264,hevc", "aac,eac3", "6"), listOf(hls.protocol, hls.container, hls.videoCodec, hls.audioCodec, hls.maxAudioChannels))
    }

    @Test
    fun codecConditionsCarryBitDepthRangeAndResolution() {
        val byCodec = WebDeviceProfile.build(rich).codecProfiles.associateBy { it.codec }
        fun value(codec: String, property: String) = byCodec.getValue(codec).conditions.single { it.property == property }.value
        assertEquals(listOf("10", "SDR|HDR10", "3840", "2160"), listOf("VideoBitDepth", "VideoRangeType", "Width", "Height").map { value("hevc", it) })
        assertEquals(listOf("8", "SDR", "1920", "1080"), listOf("VideoBitDepth", "VideoRangeType", "Width", "Height").map { value("h264", it) })
    }

    @Test
    fun burnsInAssAndImageSubtitles() {
        val methods = WebDeviceProfile.build(allSupport).subtitleProfiles.associate { it.format to it.method }
        assertEquals("External", methods["vtt"])
        listOf("ass", "ssa", "pgssub", "dvdsub").forEach { assertEquals("Encode", methods[it], it) }
    }
}

class PlaybackPlannerTest {
    private fun plan(json: String, choice: TrackChoice = TrackChoice()) = PlaybackPlanner.plan(
        SERVER, "token1", "dev1", ITEM_ID, JellyfinJson.decodeFromString(PlaybackInfoResponseDto.serializer(), json), choice,
    ).getOrThrow()

    @Test
    fun directPlayUsesTheStaticStreamWithApiKey() {
        val source = plan(playbackInfoJson(directPlay = true))
        assertEquals(PlayMethod.DirectPlay, source.method)
        assertFalse(source.isHls)
        assertEquals("$SERVER/Videos/$ITEM_ID/stream.mp4?static=true&mediaSourceId=$ITEM_ID&deviceId=dev1&ApiKey=token1", source.url)
        assertEquals(1, source.audioIndex)
        assertEquals(SUBTITLES_OFF, source.subtitleIndex)
        assertEquals(listOf(1, 2), source.audioTracks.map { it.index })
    }

    @Test
    fun otherwiseUsesTheServersHlsUrlAsGiven() {
        val source = plan(playbackInfoJson(directPlay = false, transcodingUrl = "/videos/$ITEM_ID/master.m3u8?ApiKey=token1"))
        assertEquals(PlayMethod.ServerHls, source.method)
        assertTrue(source.isHls)
        assertEquals("$SERVER/videos/$ITEM_ID/master.m3u8?ApiKey=token1", source.url)
    }

    @Test
    fun externalSubtitlesBecomeAnAbsoluteVttUrlAndBurnedInOnesDoNot() {
        val text = plan(playbackInfoJson(directPlay = true), TrackChoice(subtitleIndex = 3))
        assertEquals("$SERVER/Videos/$ITEM_ID/$ITEM_ID/Subtitles/3/0/Stream.vtt?ApiKey=token1", text.subtitleUrl)
        val burned = plan(playbackInfoJson(directPlay = false, transcodingUrl = "/x.m3u8"), TrackChoice(subtitleIndex = 4))
        assertEquals(4, burned.subtitleIndex)
        assertNull(burned.subtitleUrl)
    }

    @Test
    fun choosingANonDefaultAudioTrackTurnsDirectPlayOff() {
        val profile = WebDeviceProfile.build(allSupport)
        assertTrue(PlaybackPlanner.request(USER_ID, profile, TrackChoice(audioIndex = 1), defaultAudioIndex = 1).enableDirectPlay)
        assertFalse(PlaybackPlanner.request(USER_ID, profile, TrackChoice(audioIndex = 2), defaultAudioIndex = 1).enableDirectPlay)
    }

    @Test
    fun noRouteIsAFailure() {
        val info = JellyfinJson.decodeFromString(PlaybackInfoResponseDto.serializer(), """{"MediaSources":[],"ErrorCode":"NoCompatibleStream"}""")
        assertTrue(PlaybackPlanner.plan(SERVER, "t", "d", ITEM_ID, info, TrackChoice()).isFailure)
    }
}
