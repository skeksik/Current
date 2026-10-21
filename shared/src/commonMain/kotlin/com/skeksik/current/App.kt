package com.skeksik.current

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent
import com.skeksik.current.core.database.databaseModule
import com.skeksik.current.core.designsystem.CurrentTheme
import com.skeksik.current.core.designsystem.FocusVisibility
import com.skeksik.current.core.designsystem.LocalFocusVisibility
import com.skeksik.current.core.network.networkModule
import com.skeksik.current.data.jellyfin.jellyfinModule
import com.skeksik.current.ui.home.homeUiModule
import com.skeksik.current.ui.navigation.ShellDestination
import com.skeksik.current.ui.navigation.ShellGraph
import com.skeksik.current.ui.settings.settingsUiModule
import com.skeksik.current.ui.shell.BackInput
import com.skeksik.current.ui.shell.Shell
import org.koin.compose.KoinApplication
import org.koin.compose.getKoin
import org.koin.core.module.Module
import org.koin.dsl.koinConfiguration

/**
 * Every module the Compose app includes, and so the only place that names every feature (ADR 0014): each `*UiModule` adds its pages
 * and sidebar entry to the shell. To leave a feature out, leave out its modules.
 */
internal val appModules: List<Module> = listOf(
    databaseModule,
    networkModule,
    jellyfinModule,
    homeUiModule,
    settingsUiModule,
)

/** The app in one window (Android), fed key events by the activity through [input]. */
@Composable
fun App(input: MainWindowInput = remember { MainWindowInput() }) {
    CurrentKoin { AppContent(input) }
}

/**
 * The app's Koin graph for everything inside [content]. Desktop puts it around its windows rather than inside one, so a second window
 * (the video window and its controls, ADR 0010) shares the same graph.
 */
@Composable
fun CurrentKoin(content: @Composable () -> Unit) {
    val platformModules = platformModules()
    KoinApplication(configuration = koinConfiguration { modules(platformModules + appModules) }) { content() }
}

/**
 * What the main window feeds its content from outside the composition. [escapeIsWindowBack]: the window's own back events come only
 * from Esc, as on desktop (Compose turns an Esc nothing handled into one), so the shell never goes back on them ([BackInput]).
 */
@Stable
class MainWindowInput(escapeIsWindowBack: Boolean = false) {
    internal val focusVisibility = FocusVisibility()
    internal val back = BackInput(windowBackIsEscape = escapeIsWindowBack)

    /**
     * For every key the window gets, even when nothing has focus (after a click or tap on empty space): desktop's
     * `Window(onPreviewKeyEvent = ...)`, Android's `Activity.dispatchKeyEvent`. Feeds focus visibility, so the first Tab then shows the
     * focus ring, and handles Alt+Left, the Back key: true when it went back, and the window then consumes the key.
     */
    fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        focusVisibility.onKeyEvent(event)
        return back.onKeyEvent(event)
    }

    /** Desktop: the mouse back button was pressed anywhere in the window. */
    fun onBackButton() {
        back.onBackButton()
    }

    /**
     * Android: the window left touch mode. In touch mode the first navigation key (Tab, an arrow, Enter) never reaches
     * [onPreviewKeyEvent]: Android consumes it to leave touch mode and focuses the first element itself, which should show its ring.
     */
    fun onLeftTouchMode() {
        focusVisibility.onKeyboardNavigation()
    }
}

/** The main window's content: the theme, the sidebar and the pages. Needs [CurrentKoin] around it. */
@Composable
fun AppContent(input: MainWindowInput = remember { MainWindowInput() }) {
    val koin = getKoin()
    val graph = remember(koin) { ShellGraph(koin.getAll<ShellDestination>()) }
    CompositionLocalProvider(LocalFocusVisibility provides input.focusVisibility) {
        CurrentTheme { Shell(graph, input.back) }
    }
}

/** Definitions only the platform can make, such as Android's application `Context`. */
@Composable
internal expect fun platformModules(): List<Module>
