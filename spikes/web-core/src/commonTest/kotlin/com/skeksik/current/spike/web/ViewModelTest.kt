package com.skeksik.current.spike.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeEngine : PlayerEngine {
    data class Load(val url: String, val isHls: Boolean, val startSeconds: Double, val subtitleUrl: String?, val videoRanges: List<String> = listOf("SDR"))

    override val state = MutableStateFlow(EngineState())
    val loads = mutableListOf<Load>()
    var fullscreenToggles = 0
    var released = false

    override fun load(url: String, isHls: Boolean, startSeconds: Double, subtitleUrl: String?, videoRanges: List<String>) {
        loads += Load(url, isHls, startSeconds, subtitleUrl, videoRanges)
    }

    override fun play() = state.update { it.copy(paused = false) }
    override fun pause() = state.update { it.copy(paused = true) }
    override fun seekTo(seconds: Double) = state.update { it.copy(positionSeconds = seconds) }
    override fun setVolume(volume: Double) = state.update { it.copy(volume = volume) }
    override fun setMuted(muted: Boolean) = state.update { it.copy(muted = muted) }
    override fun toggleFullscreen() {
        fullscreenToggles++
    }

    override fun setMetadata(title: String, artist: String?, artworkUrl: String?) {}
    override fun release() {
        released = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
abstract class ViewModelTestBase {
    @AfterTest
    fun resetDispatchers() = Dispatchers.resetMain()

    /** ViewModels run on Main; the server, the graph's detached work and Main share the test scheduler. */
    fun TestScope.setUp(): Pair<FakeServer, CoroutineScope> {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return FakeServer(dispatcher) to CoroutineScope(dispatcher)
    }
}

class SignInViewModelTest : ViewModelTestBase() {
    @Test
    fun signsInWithTheMediaBrowserHeaderAndKeepsTheSession() = runTest {
        val (server, scope) = setUp()
        server.handler = { respondJson("""{"AccessToken":"tok","User":{"Id":"$USER_ID","Name":"me"},"ServerId":"s"}""") }
        val store = InMemoryKeyValueStore()
        val graph = server.graph(store, scope)
        val holder = holdViewModel { graph.signInViewModel() }

        holder.viewModel.signIn("jellyfin.example.org/", " me ", "secret")

        val (request, body) = server.requestsTo("/Users/AuthenticateByName").single()
        assertEquals("$SERVER/Users/AuthenticateByName", request.url.toString())
        val header = request.headers[HttpHeaders.Authorization].orEmpty()
        assertTrue(header.startsWith("MediaBrowser Client=") && "DeviceId=\"${graph.clientInfo.deviceId}\"" in header && "Token=" !in header)
        assertEquals("me", body!!["Username"]!!.jsonPrimitive.content)
        assertEquals("secret", body["Pw"]!!.jsonPrimitive.content)

        assertEquals(Session(SERVER, "tok", USER_ID, "me"), graph.session.value)
        assertFalse(holder.viewModel.state.value.busy)
        // A reload reads the same session and device id from storage.
        val reloaded = server.graph(store, scope)
        assertEquals(graph.session.value, reloaded.session.value)
        assertEquals(graph.clientInfo.deviceId, reloaded.clientInfo.deviceId)
        assertEquals(SERVER, reloaded.signInViewModel().state.value.serverUrl)
        holder.close()
    }

    @Test
    fun showsWrongPasswordAndStaysSignedOut() = runTest {
        val (server, scope) = setUp()
        server.handler = { respondJson("", HttpStatusCode.Unauthorized) }
        val graph = server.graph(scope = scope)
        val holder = holdViewModel { graph.signInViewModel() }

        holder.viewModel.signIn(SERVER, "me", "wrong")

        assertNull(graph.session.value)
        assertEquals("Wrong username or password, or the session has expired.", holder.viewModel.state.value.error)
        holder.close()
    }

    @Test
    fun signOutRevokesPasswordSessionsButNeverApiKeySessions() = runTest {
        val (server, scope) = setUp()
        server.graph(signedInStore(), scope).signOut()
        assertEquals(1, server.requestsTo("/Sessions/Logout").size)
        server.graph(signedInStore(viaApiKey = true), scope).signOut()
        assertEquals(1, server.requestsTo("/Sessions/Logout").size)
    }
}

class BrowseViewModelTest : ViewModelTestBase() {
    @Test
    fun homeListsTheUsersLibraries() = runTest {
        val (server, scope) = setUp()
        server.handler = { respondJson("""{"Items":[{"Id":"$ITEM_ID","Name":"Movies","ImageTags":{"Primary":"t1"}}]}""") }
        val holder = holdViewModel { server.graph(signedInStore(), scope).homeViewModel() }

        val libraries = assertIs<LoadState.Ready<List<Library>>>(holder.viewModel.state.value).value
        assertEquals(listOf(Library(ITEM_ID, "Movies", "$SERVER/Items/$ITEM_ID/Images/Primary?tag=t1&fillHeight=300&quality=90")), libraries)
        val request = server.requestsTo("/UserViews").single().first
        assertEquals(USER_ID, request.url.parameters["userId"])
        assertTrue("Token=\"token1\"" in request.headers[HttpHeaders.Authorization].orEmpty())
        holder.close()
    }

    @Test
    fun itemPageLoadsChildrenOnlyForFolders() = runTest {
        val (server, scope) = setUp()
        server.handler = { request ->
            if (request.url.encodedPath.endsWith("/Items")) {
                respondJson("""{"Items":[{"Id":"$USER_ID","Name":"Season 1","IsFolder":true}]}""")
            } else {
                respondJson("""{"Id":"$ITEM_ID","Name":"Show","Overview":"About.","IsFolder":true}""")
            }
        }
        val holder = holdViewModel { server.graph(signedInStore(), scope).itemViewModel(ITEM_ID) }

        val details = assertIs<LoadState.Ready<ItemDetails>>(holder.viewModel.state.value).value
        assertEquals("About.", details.item.overview)
        assertEquals(listOf("Season 1"), details.children.map { it.name })
        assertEquals(ITEM_ID, server.requestsTo("/Items").single().first.url.parameters["parentId"])
        holder.close()
    }

    @Test
    fun failuresBecomeMessages() = runTest {
        val (server, scope) = setUp()
        server.handler = { respondJson("", HttpStatusCode.InternalServerError) }
        val holder = holdViewModel { server.graph(signedInStore(), scope).libraryViewModel(ITEM_ID) }
        assertEquals(LoadState.Failed("The server answered 500."), holder.viewModel.state.value.items)
        holder.close()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest : ViewModelTestBase() {
    private val hlsUrl = "/videos/$ITEM_ID/master.m3u8?ApiKey=token1"

    private fun FakeServer.answer(playbackInfo: () -> String) {
        handler = { request ->
            when {
                request.url.encodedPath.endsWith("/PlaybackInfo") -> respondJson(playbackInfo())
                request.url.encodedPath.endsWith("/Items/$ITEM_ID") -> respondJson("""{"Id":"$ITEM_ID","Name":"Film"}""")
                else -> respondJson("{}")
            }
        }
    }

    private fun FakeServer.playbackRequests() = requestsTo("/PlaybackInfo").map { it.second!! }

    @Test
    fun loadsTheDirectRouteIntoTheEngineOnceAttached() = runTest {
        val (server, scope) = setUp()
        server.answer { playbackInfoJson(directPlay = true) }
        val holder = holdViewModel { server.graph(signedInStore(), scope).playerViewModel(ITEM_ID) }
        val engine = FakeEngine()

        holder.viewModel.attach(engine)

        val state = holder.viewModel.state.value
        assertEquals("Film", state.title)
        assertEquals(PlayMethod.DirectPlay, state.method)
        assertEquals(listOf(1, 2), state.audioTracks.map { it.index })
        assertFalse(engine.loads.single().isHls)
        assertTrue(engine.loads.single().url.contains("/Videos/$ITEM_ID/stream.mp4?static=true"))
        val profile = server.playbackRequests().single()["DeviceProfile"]
        assertNotNull(profile)
        holder.close()
        assertTrue(engine.released)
    }

    @Test
    fun switchingAudioAsksAgainWithoutDirectPlayAndResumesAtThePosition() = runTest {
        val (server, scope) = setUp()
        var call = 0
        server.answer { if (call++ == 0) playbackInfoJson(directPlay = true) else playbackInfoJson(false, hlsUrl, playSessionId = "ps2") }
        val holder = holdViewModel { server.graph(signedInStore(), scope).playerViewModel(ITEM_ID) }
        val engine = FakeEngine()
        holder.viewModel.attach(engine)
        engine.state.update { it.copy(positionSeconds = 42.0) }

        holder.viewModel.selectAudio(2)

        val second = server.playbackRequests()[1]
        assertEquals(2, second["AudioStreamIndex"]!!.jsonPrimitive.int)
        assertEquals(ITEM_ID, second["MediaSourceId"]!!.jsonPrimitive.content)
        assertFalse(second["EnableDirectPlay"]!!.jsonPrimitive.boolean)
        assertEquals(FakeEngine.Load("$SERVER$hlsUrl", true, 42.0, null), engine.loads.last())
        assertEquals(PlayMethod.ServerHls, holder.viewModel.state.value.method)
        holder.close()
    }

    @Test
    fun choosingSubtitlesAddsTheExternalTrack() = runTest {
        val (server, scope) = setUp()
        server.answer { playbackInfoJson(directPlay = true) }
        val holder = holdViewModel { server.graph(signedInStore(), scope).playerViewModel(ITEM_ID) }
        val engine = FakeEngine()
        holder.viewModel.attach(engine)

        holder.viewModel.selectSubtitle(3)

        assertEquals(3, server.playbackRequests()[1]["SubtitleStreamIndex"]!!.jsonPrimitive.int)
        assertEquals("$SERVER/Videos/$ITEM_ID/$ITEM_ID/Subtitles/3/0/Stream.vtt?ApiKey=token1", engine.loads.last().subtitleUrl)
        assertEquals(3, holder.viewModel.state.value.subtitleIndex)
        holder.close()
    }

    @Test
    fun directPlayErrorsRetryOnceThroughTheServer() = runTest {
        val (server, scope) = setUp()
        var call = 0
        server.answer { if (call++ == 0) playbackInfoJson(directPlay = true) else playbackInfoJson(false, hlsUrl) }
        val holder = holdViewModel { server.graph(signedInStore(), scope).playerViewModel(ITEM_ID) }
        val engine = FakeEngine()
        holder.viewModel.attach(engine)

        engine.state.update { it.copy(error = "Media error 4") }

        assertFalse(server.playbackRequests()[1]["EnableDirectPlay"]!!.jsonPrimitive.boolean)
        assertTrue(engine.loads.last().isHls)
        assertNull(holder.viewModel.state.value.error)
        holder.close()
    }

    @Test
    fun hlsTranscodesArePingedAndStoppedWhenThePageCloses() = runTest {
        val (server, scope) = setUp()
        server.answer { playbackInfoJson(false, hlsUrl, playSessionId = "ps9") }
        val holder = holdViewModel { server.graph(signedInStore(), scope).playerViewModel(ITEM_ID) }
        holder.viewModel.attach(FakeEngine())

        advanceTimeBy(PlayerViewModel.PING_INTERVAL_MS + 1)
        assertEquals("ps9", server.requestsTo("/Sessions/Playing/Ping").single().first.url.parameters["playSessionId"])
        holder.close()

        val stop = server.requestsTo("/Videos/ActiveEncodings").single().first
        assertEquals(HttpMethod.Delete, stop.method)
        assertEquals("ps9", stop.url.parameters["playSessionId"])
        assertTrue(server.requestsTo("/Sessions/Playing").none { !it.first.url.encodedPath.endsWith("/Ping") }, "no progress reports")
    }

    @Test
    fun keyboardShortcuts() = runTest {
        val (server, scope) = setUp()
        server.answer { playbackInfoJson(directPlay = true) }
        val holder = holdViewModel { server.graph(signedInStore(), scope).playerViewModel(ITEM_ID) }
        val engine = FakeEngine()
        holder.viewModel.attach(engine)
        val vm = holder.viewModel

        assertTrue(vm.onKey(" "))
        assertFalse(engine.state.value.paused)
        engine.state.update { it.copy(positionSeconds = 30.0, durationSeconds = 100.0) }
        assertTrue(vm.onKey("ArrowLeft"))
        assertEquals(20.0, engine.state.value.positionSeconds)
        assertTrue(vm.onKey("ArrowRight"))
        assertEquals(30.0, engine.state.value.positionSeconds)
        assertTrue(vm.onKey("f"))
        assertEquals(1, engine.fullscreenToggles)
        assertTrue(vm.onKey("ArrowDown"))
        assertEquals(0.9, engine.state.value.volume, 1e-9)
        assertFalse(vm.onKey("x"))
        holder.close()
    }

    @Test
    fun controlsHideWhilePlayingAndComeBackOnActivity() = runTest {
        val (server, scope) = setUp()
        server.answer { playbackInfoJson(directPlay = true) }
        val holder = holdViewModel { server.graph(signedInStore(), scope).playerViewModel(ITEM_ID) }
        val engine = FakeEngine()
        holder.viewModel.attach(engine)

        engine.play()
        advanceTimeBy(PlayerViewModel.HIDE_AFTER_MS + 1)
        assertFalse(holder.viewModel.state.value.controlsVisible)
        holder.viewModel.onActivity()
        assertTrue(holder.viewModel.state.value.controlsVisible)
        engine.pause()
        advanceTimeBy(PlayerViewModel.HIDE_AFTER_MS + 1)
        assertTrue(holder.viewModel.state.value.controlsVisible)
        holder.close()
    }
}
