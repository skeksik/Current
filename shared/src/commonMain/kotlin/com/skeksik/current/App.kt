package com.skeksik.current

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.skeksik.current.core.database.databaseModule
import com.skeksik.current.core.designsystem.CurrentTheme
import com.skeksik.current.core.network.networkModule
import com.skeksik.current.data.jellyfin.jellyfinModule
import com.skeksik.current.ui.home.homeUiModule
import com.skeksik.current.ui.navigation.ShellDestination
import com.skeksik.current.ui.navigation.ShellGraph
import com.skeksik.current.ui.settings.settingsUiModule
import com.skeksik.current.ui.shell.Shell
import org.koin.compose.KoinApplication
import org.koin.compose.getKoin
import org.koin.core.module.Module
import org.koin.dsl.koinConfiguration

/**
 * Every module the Compose app includes, and so the only place that names every feature (ADR 0014): each `*UiModule` adds its pages
 * and sidebar entry to the shell. To leave a feature out, leave out its modules.
 */
private val appModules: List<Module> = listOf(
    databaseModule,
    networkModule,
    jellyfinModule,
    homeUiModule,
    settingsUiModule,
)

/** The app in one window (Android). */
@Composable
fun App() {
    CurrentKoin { AppContent() }
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

/** The main window's content: the theme, the sidebar and the pages. Needs [CurrentKoin] around it. */
@Composable
fun AppContent() {
    val koin = getKoin()
    val graph = remember(koin) { ShellGraph(koin.getAll<ShellDestination>()) }
    CurrentTheme { Shell(graph) }
}

/** Definitions only the platform can make, such as Android's application `Context`. */
@Composable
internal expect fun platformModules(): List<Module>
