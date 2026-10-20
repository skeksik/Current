package com.skeksik.current.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeksik.current.core.designsystem.CurrentIcons
import com.skeksik.current.core.designsystem.EmptyState
import com.skeksik.current.core.designsystem.StateAction
import com.skeksik.current.feature.home.HomeUiState
import com.skeksik.current.feature.home.HomeViewModel
import com.skeksik.current.ui.navigation.AddServerRoute
import com.skeksik.current.ui.navigation.Navigator
import com.skeksik.current.ui.navigation.ShellDestination
import com.skeksik.current.ui.navigation.ShellPage
import com.skeksik.current.ui.navigation.SidebarEntry
import com.skeksik.current.ui.navigation.SignInRoute
import com.skeksik.current.ui.navigation.pageViewModel
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.home_add_server
import com.skeksik.current.ui.resources.home_no_server_message
import com.skeksik.current.ui.resources.home_no_server_title
import com.skeksik.current.ui.resources.home_sign_in
import com.skeksik.current.ui.resources.home_signed_in_as
import com.skeksik.current.ui.resources.home_signed_in_message
import com.skeksik.current.ui.resources.home_signed_in_title
import com.skeksik.current.ui.resources.home_signed_out_message
import com.skeksik.current.ui.resources.home_signed_out_title
import com.skeksik.current.ui.resources.nav_home
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource
import org.koin.dsl.bind
import org.koin.dsl.module

@Serializable
data object HomeRoute

/** Home: the first page of the app. */
object HomeDestination : ShellDestination {
    override val pages = listOf(ShellPage(HomeRoute::class) { _, navigator -> HomeScreen(navigator) })
    override val sidebar = SidebarEntry(HomeRoute, Res.string.nav_home, CurrentIcons.Home, order = 0)
}

/** Koin definitions of the home pages. */
val homeUiModule = module {
    single { HomeDestination } bind ShellDestination::class
}

@Composable
private fun HomeScreen(navigator: Navigator) {
    val viewModel = pageViewModel { HomeViewModel(get(), get()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val shown = state) {
        HomeUiState.NoServer -> EmptyState(
            title = stringResource(Res.string.home_no_server_title),
            message = stringResource(Res.string.home_no_server_message),
            icon = CurrentIcons.Server,
            action = StateAction(stringResource(Res.string.home_add_server)) { navigator.open(AddServerRoute) },
        )
        is HomeUiState.SignedOut -> EmptyState(
            title = stringResource(Res.string.home_signed_out_title),
            message = stringResource(Res.string.home_signed_out_message, shown.serverName),
            icon = CurrentIcons.Server,
            action = StateAction(stringResource(Res.string.home_sign_in)) { navigator.open(SignInRoute(shown.server, shown.serverName)) },
        )
        is HomeUiState.SignedIn -> EmptyState(
            title = stringResource(Res.string.home_signed_in_title, shown.serverName),
            message = when (val user = shown.userName) {
                null -> stringResource(Res.string.home_signed_in_message)
                else -> stringResource(Res.string.home_signed_in_as, user, shown.serverName)
            },
            icon = CurrentIcons.Home,
        )
    }
}
