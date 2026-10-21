package com.skeksik.current.ui.settings

import androidx.navigation.toRoute
import com.skeksik.current.core.designsystem.CurrentIcons
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.ui.navigation.AddServerRoute
import com.skeksik.current.ui.navigation.SettingsRoute
import com.skeksik.current.ui.navigation.ShellDestination
import com.skeksik.current.ui.navigation.ShellPage
import com.skeksik.current.ui.navigation.shellDestination
import com.skeksik.current.ui.navigation.SidebarEntry
import com.skeksik.current.ui.navigation.SignInRoute
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.nav_settings
import kotlinx.serialization.Serializable
import org.koin.dsl.module

// Pages only settings opens, so their routes stay here rather than in the shared contract.

/** Settings > Servers > a server's addresses, in priority order (ADR 0019); [name] is the server's, shown while the page loads. */
@Serializable
internal data class ServerAddressesRoute(val service: String, val key: String, val name: String) {
    constructor(server: ServerRef, name: String) : this(server.service.value, server.key, name)

    val server: ServerRef get() = ServerRef(ServiceType(service), key)
}

/** Settings > Servers > Addresses > Add address: accepted only where the same server answers. */
@Serializable
internal data class AddServerAddressRoute(val service: String, val key: String, val name: String) {
    constructor(server: ServerRef, name: String) : this(server.service.value, server.key, name)

    val server: ServerRef get() = ServerRef(ServiceType(service), key)
}

/** Settings: servers (add, sign in, sign out, remove, their addresses) and about. */
object SettingsDestination : ShellDestination {
    override val pages = listOf(
        ShellPage(SettingsRoute::class) { _, navigator -> SettingsScreen(navigator) },
        ShellPage(AddServerRoute::class) { _, navigator -> AddServerScreen(navigator) },
        ShellPage(SignInRoute::class) { entry, navigator -> SignInScreen(entry.toRoute<SignInRoute>(), navigator) },
        ShellPage(ServerAddressesRoute::class) { entry, navigator -> ServerAddressesScreen(entry.toRoute<ServerAddressesRoute>(), navigator) },
        ShellPage(AddServerAddressRoute::class) { entry, navigator ->
            val route = entry.toRoute<AddServerAddressRoute>()
            AddServerScreen(navigator, addTo = route.server, name = route.name)
        },
    )

    /** Last in the sidebar, whatever features come before it. */
    override val sidebar = SidebarEntry(SettingsRoute, Res.string.nav_settings, CurrentIcons.Settings, order = 1000)
}

/** Koin definitions of the settings pages. */
val settingsUiModule = module {
    shellDestination(SettingsDestination)
}
