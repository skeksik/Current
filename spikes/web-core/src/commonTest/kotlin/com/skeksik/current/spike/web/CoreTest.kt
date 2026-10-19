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

class WebDeviceProfileTest {
    private fun containers(s: MediaSupport) = WebDeviceProfile.build(s).directPlayProfiles.map { it.container }

    @Test
    fun mkvOnlyWhereTheProbePasses() {
        assertEquals(listOf("mp4,m4v", "webm"), containers(allSupport))
        assertEquals(listOf("mp4,m4v", "webm", "mkv"), containers(allSupport.copy(mkv = true)))
    }

    @Test
    fun neverDirectPlaysDolbyOrDtsAudio() {
        val audio = WebDeviceProfile.build(allSupport.copy(mkv = true)).directPlayProfiles.flatMap { it.audioCodec.orEmpty().split(',') }
        listOf("ac3", "eac3", "dts", "truehd").forEach { assertFalse(it in audio, it) }
    }

    @Test
    fun onlyProbedCodecsAreOffered() {
        val mp4 = WebDeviceProfile.build(allSupport.copy(hevc = true, av1 = false)).directPlayProfiles.first()
        assertEquals("h264,hevc,vp9", mp4.videoCodec)
    }

    @Test
    fun transcodesToHlsAndBurnsInAssAndImageSubtitles() {
        val profile = WebDeviceProfile.build(allSupport)
        val hls = profile.transcodingProfiles.single()
        assertEquals(listOf("hls", "ts", "h264", "aac"), listOf(hls.protocol, hls.container, hls.videoCodec, hls.audioCodec))
        val methods = profile.subtitleProfiles.associate { it.format to it.method }
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
