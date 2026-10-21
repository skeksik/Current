package com.skeksik.current.web

import com.skeksik.current.core.database.databaseModule
import com.skeksik.current.core.model.AppInfo
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.networkModule
import com.skeksik.current.core.playback.web.probeMediaSupport
import com.skeksik.current.data.jellyfin.jellyfinModule
import com.skeksik.current.feature.home.HomeViewModel
import com.skeksik.current.feature.settings.AboutViewModel
import com.skeksik.current.feature.settings.PlaybackSupportViewModel
import com.skeksik.current.feature.settings.ServerSetupViewModel
import com.skeksik.current.feature.settings.ServersViewModel
import com.skeksik.current.feature.settings.SignInViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.koin.core.Koin
import org.koin.dsl.koinApplication

/**
 * The current page: [kind] is "home", "settings", "addServer", "signIn", "playbackSupport" or "notFound". [hasBack] is true for pages
 * below a sidebar entry's page, which show a Back button. [opener] is set when Back or Forward returned to this page: the
 * `data-focus-id` of what opened the page that left, to focus again. Each navigation gets a new view, even to the same page.
 */
@JsExport
class RouteView(val kind: String, val path: String, val hasBack: Boolean, val opener: String?)

/** The whole core as the React app sees it; one per page load. */
@JsExport
class CurrentWeb {
    private val router = BrowserRouter()

    /**
     * The same modules as the Compose app's, minus its pages (ADR 0014). Created at once, so the saved servers are loaded and saved
     * sessions restored from `localStorage` (ADR 0017) before the first page reads them: a reload stays signed in.
     */
    private val koin: Koin = koinApplication { modules(databaseModule, networkModule, jellyfinModule) }.koin

    /** The browser probes run once per page load, when a page first needs them. */
    private val mediaSupport = mainScope.async(start = CoroutineStart.LAZY) { probeMediaSupport() }

    val route: Observable<RouteView> = router.shown.observe { it.route.toView(it.opener) }

    val version: String = AppInfo.VERSION

    // Holds no state or coroutines, so it lives as long as the page load.
    private val about by lazy { AboutViewModel(koin.get(), koin.get()) }

    /** Settings > About's "Copy diagnostics": the version, the browser and the request log's last lines, without hosts or secrets. */
    fun diagnostics(): String = about.diagnostics()

    /**
     * Navigates for a plain click and returns true (call `preventDefault`); leaves other clicks to the browser. [opener] is the link's
     * `data-focus-id`, focused again when Back returns here.
     */
    fun onLinkClick(path: String, opener: String?, button: Int, ctrl: Boolean, meta: Boolean, shift: Boolean, alt: Boolean): Boolean {
        if (!isPlainClick(button, ctrl, meta, shift, alt)) return false
        router.navigate(Route.parse(path), opener)
        return true
    }

    /** Opens [path] as a new history step, as a button that leads to another page does; [opener] as for [onLinkClick]. */
    fun open(path: String, opener: String?) = router.navigate(Route.parse(path), opener)

    /** Shows [path] in place of the current page, so Back skips this one (a finished step, such as adding a server). */
    fun replace(path: String) = router.replace(Route.parse(path))

    /** Goes back one step; from the first page opened in this tab, to its parent rather than out of the app. */
    fun back() = router.back()

    fun homePage() = HomePage(holdViewModel { HomeViewModel(koin.get(), koin.get()) })

    fun settingsPage() = SettingsPage(holdViewModel { ServersViewModel(koin.get(), koin.get(), koin.get(), koin.get()) })

    fun addServerPage() =
        AddServerPage(holdViewModel { ServerSetupViewModel(koin.get(), koin.get(), koin.get<MutableCapabilitiesState>()) })

    /** The sign-in page for the server named by [path], a sign-in page's path. */
    fun signInPage(path: String): SignInPage {
        val server = requireNotNull(Route.parse(path) as? Route.SignIn) { "Not a sign-in page: $path" }.server
        val name = koin.get<ServerRepository>().server(server)?.name
        return SignInPage(holdViewModel { SignInViewModel(koin.get(), server) }, name)
    }

    fun playbackSupportPage() = PlaybackSupportPage(holdViewModel { PlaybackSupportViewModel { mediaSupport.await() } })
}

private fun Route.toView(opener: String?) = RouteView(
    kind = when (this) {
        Route.Home -> "home"
        Route.Settings -> "settings"
        Route.AddServer -> "addServer"
        is Route.SignIn -> "signIn"
        Route.PlaybackSupport -> "playbackSupport"
        is Route.NotFound -> "notFound"
    },
    path = path,
    hasBack = parent != null && this !is Route.NotFound,
    opener = opener,
)
