@file:OptIn(ExperimentalJsExport::class)

package com.skeksik.current.spike.webts

import com.skeksik.current.spike.web.HtmlVideoEngine
import com.skeksik.current.spike.web.ItemDetails
import com.skeksik.current.spike.web.Library
import com.skeksik.current.spike.web.LoadState
import com.skeksik.current.spike.web.MediaItem
import com.skeksik.current.spike.web.PlayerMenu
import com.skeksik.current.spike.web.PlayerUiState
import com.skeksik.current.spike.web.PlayerViewModel
import com.skeksik.current.spike.web.Route
import com.skeksik.current.spike.web.SUBTITLES_OFF
import com.skeksik.current.spike.web.Track
import com.skeksik.current.spike.web.ViewModelHolder
import com.skeksik.current.spike.web.WebApp
import com.skeksik.current.spike.web.attachPlayerKeys
import com.skeksik.current.spike.web.holdViewModel
import com.skeksik.current.spike.web.isPlainClick
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement

private val scope = MainScope()

/**
 * A `StateFlow` for `useSyncExternalStore`: [getSnapshot] returns the same object until the flow emits a new value, [subscribe]
 * returns the unsubscribe function.
 */
@JsExport
class Observable<T> internal constructor(private val flow: StateFlow<Any?>, private val map: (Any?) -> T) {
    private var source: Any? = Unset
    private var snapshot: T? = null

    fun getSnapshot(): T {
        val value = flow.value
        if (value !== source) {
            source = value
            snapshot = map(value)
        }
        @Suppress("UNCHECKED_CAST")
        return snapshot as T
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        val job = scope.launch { flow.drop(1).collect { listener() } }
        return { job.cancel() }
    }

    private object Unset
}

@Suppress("UNCHECKED_CAST")
private fun <S, T> StateFlow<S>.observe(map: (S) -> T) = Observable(this as StateFlow<Any?>) { map(it as S) }

@JsExport
class RouteView(val kind: String, val id: String?, val path: String)

@JsExport
class SessionView(val userName: String)

@JsExport
class CardView(val id: String, val title: String, val sub: String?, val imageUrl: String?, val wide: Boolean, val path: String)

/** A list page: loading, failed, or ready with cards. */
@JsExport
class ListView(val title: String, val loading: Boolean, val error: String?, val cards: Array<CardView>)

@JsExport
class ItemView(
    val loading: Boolean,
    val error: String?,
    val title: String,
    val meta: String,
    val overview: String?,
    val imageUrl: String?,
    val playPath: String?,
    val children: Array<CardView>,
)

@JsExport
class TrackView(val index: Int, val label: String, val selected: Boolean)

@JsExport
class PlayerView(
    val title: String,
    val loading: Boolean,
    val error: String?,
    val method: String?,
    val audio: Array<TrackView>,
    val subtitles: Array<TrackView>,
    val position: Double,
    val duration: Double,
    val positionText: String,
    val durationText: String,
    val paused: Boolean,
    val buffering: Boolean,
    val fullscreen: Boolean,
    val controlsVisible: Boolean,
    /** "Audio", "Subtitles" or null. */
    val openMenu: String?,
)

@JsExport
class SignInView(val serverUrl: String, val busy: Boolean, val error: String?)

/** A page's ViewModel; React disposes it when the page unmounts. */
@JsExport
abstract class Page internal constructor(private val holder: ViewModelHolder<*>) {
    fun dispose() = holder.close()
}

@JsExport
class SignInPage internal constructor(app: WebApp, holder: ViewModelHolder<com.skeksik.current.spike.web.SignInViewModel> = holdViewModel { app.graph.signInViewModel() }) : Page(holder) {
    private val vm = holder.viewModel
    val state: Observable<SignInView> = vm.state.observe { SignInView(it.serverUrl, it.busy, it.error) }
    fun signIn(serverUrl: String, username: String, password: String) = vm.signIn(serverUrl, username, password)
}

@JsExport
class ListPage internal constructor(holder: ViewModelHolder<*>, val state: Observable<ListView>) : Page(holder)

@JsExport
class ItemPage internal constructor(holder: ViewModelHolder<*>, val state: Observable<ItemView>) : Page(holder)

@JsExport
class PlayerPage internal constructor(private val holder: ViewModelHolder<PlayerViewModel>) : Page(holder) {
    private val vm = holder.viewModel
    private var keys: AutoCloseable? = null
    val state: Observable<PlayerView> = vm.state.observe { it.toView() }

    /** Called once the `<video>` and its container exist; also installs the player shortcuts. */
    fun attach(video: HTMLVideoElement, container: HTMLElement) {
        vm.attach(HtmlVideoEngine(video, container))
        keys = attachPlayerKeys(vm)
    }

    fun detach() {
        keys?.close()
        keys = null
        vm.detach()
    }

    fun togglePlay() = vm.togglePlay()
    fun seekTo(seconds: Double) = vm.seekTo(seconds)
    fun toggleFullscreen() = vm.toggleFullscreen()
    fun toggleMenu(menu: String) = vm.toggleMenu(PlayerMenu.valueOf(menu))
    fun selectAudio(index: Int) = vm.selectAudio(index)
    fun selectSubtitle(index: Int) = vm.selectSubtitle(index)
    fun onActivity() = vm.onActivity()
}

/** The whole core as the React app sees it. */
@JsExport
class CurrentWeb {
    private val app = WebApp.create()

    val route: Observable<RouteView> = app.screen.observe { it.toView() }
    val session: Observable<SessionView?> = app.graph.session.observe { it?.let { s -> SessionView(s.userName) } }

    /** Navigates for a plain click and returns true (call `preventDefault`); leaves other clicks to the browser. */
    fun onLinkClick(path: String, button: Int, ctrl: Boolean, meta: Boolean, shift: Boolean, alt: Boolean): Boolean {
        if (!isPlainClick(button, ctrl, meta, shift, alt)) return false
        app.router.navigate(Route.parse(path))
        return true
    }

    fun signOut() = app.graph.signOut()

    fun signInPage() = SignInPage(app)

    fun homePage(): ListPage {
        val holder = holdViewModel { app.graph.homeViewModel() }
        return ListPage(holder, holder.viewModel.state.observe { state -> state.toListView("Libraries") { it.toCard() } })
    }

    fun libraryPage(id: String): ListPage {
        val holder = holdViewModel { app.graph.libraryViewModel(id) }
        return ListPage(holder, holder.viewModel.state.observe { state -> state.items.toListView(state.title) { it.toCard() } })
    }

    fun itemPage(id: String): ItemPage {
        val holder = holdViewModel { app.graph.itemViewModel(id) }
        return ItemPage(holder, holder.viewModel.state.observe { it.toItemView() })
    }

    fun playerPage(id: String) = PlayerPage(holdViewModel { app.graph.playerViewModel(id) })
}

private fun Route.toView() = when (this) {
    Route.Home -> RouteView("home", null, path)
    Route.SignIn -> RouteView("signIn", null, path)
    is Route.Library -> RouteView("library", id, path)
    is Route.Item -> RouteView("item", id, path)
    is Route.Player -> RouteView("player", id, path)
    is Route.NotFound -> RouteView("notFound", null, path)
}

private fun <T> LoadState<List<T>>.toListView(title: String, card: (T) -> CardView) = when (this) {
    LoadState.Loading -> ListView(title, true, null, emptyArray())
    is LoadState.Failed -> ListView(title, false, message, emptyArray())
    is LoadState.Ready -> ListView(title, false, null, value.map(card).toTypedArray())
}

private fun Library.toCard() = CardView(id, name, null, imageUrl, true, Route.Library(id).path)

private fun MediaItem.toCard() = CardView(id, name, year?.toString(), imageUrl, imageAspectRatio > 1, Route.Item(id).path)

private fun LoadState<ItemDetails>.toItemView() = when (this) {
    LoadState.Loading -> ItemView(true, null, "", "", null, null, null, emptyArray())
    is LoadState.Failed -> ItemView(false, message, "", "", null, null, null, emptyArray())
    is LoadState.Ready -> with(value.item) {
        ItemView(
            loading = false,
            error = null,
            title = name,
            meta = listOfNotNull(seriesName, year?.toString(), type).joinToString(" · "),
            overview = overview,
            imageUrl = imageUrl,
            playPath = if (isPlayable) Route.Player(id).path else null,
            children = value.children.map { it.toCard() }.toTypedArray(),
        )
    }
}

private fun List<Track>.toViews(selected: Int?) = map { TrackView(it.index, it.label, it.index == selected) }.toTypedArray()

private fun PlayerUiState.toView() = PlayerView(
    title = listOfNotNull(subtitle, title).joinToString(" · "),
    loading = loading,
    error = error,
    method = method?.label,
    audio = audioTracks.toViews(audioIndex),
    subtitles = (listOf(Track(SUBTITLES_OFF, "Off", false)) + subtitleTracks).toViews(subtitleIndex),
    position = playback.positionSeconds,
    duration = playback.durationSeconds,
    positionText = positionText,
    durationText = durationText,
    paused = playback.paused,
    buffering = playback.buffering,
    fullscreen = playback.fullscreen,
    controlsVisible = controlsVisible,
    openMenu = openMenu?.name,
)
