package com.skeksik.current.ui.settings

import androidx.navigation.toRoute
import com.skeksik.current.core.designsystem.CurrentIcons
import com.skeksik.current.ui.navigation.AddServerRoute
import com.skeksik.current.ui.navigation.ShellDestination
import com.skeksik.current.ui.navigation.ShellPage
import com.skeksik.current.ui.navigation.SidebarEntry
import com.skeksik.current.ui.navigation.SignInRoute
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.nav_settings
import kotlinx.serialization.Serializable
import org.koin.dsl.bind
import org.koin.dsl.module

/** Settings: servers (add, sign in, sign out, remove) and about. */
@Serializable
data object SettingsRoute

object SettingsDestination : ShellDestination {
    override val pages = listOf(
        ShellPage(SettingsRoute::class) { _, navigator -> SettingsScreen(navigator) },
        ShellPage(AddServerRoute::class) { _, navigator -> AddServerScreen(navigator) },
        ShellPage(SignInRoute::class) { entry, navigator -> SignInScreen(entry.toRoute<SignInRoute>(), navigator) },
    )

    /** Last in the sidebar, whatever features come before it. */
    override val sidebar = SidebarEntry(SettingsRoute, Res.string.nav_settings, CurrentIcons.Settings, order = 1000)
}

/** Koin definitions of the settings pages. */
val settingsUiModule = module {
    single { SettingsDestination } bind ShellDestination::class
}
