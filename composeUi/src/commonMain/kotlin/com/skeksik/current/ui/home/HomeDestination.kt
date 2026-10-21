package com.skeksik.current.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeksik.current.core.designsystem.CurrentIcons
import com.skeksik.current.core.designsystem.CurrentTokens.Size
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing
import com.skeksik.current.core.designsystem.EmptyState
import com.skeksik.current.core.designsystem.StateAction
import com.skeksik.current.feature.home.HomeLocalNetworkState
import com.skeksik.current.feature.home.HomeLocalNetworkViewModel
import com.skeksik.current.feature.home.HomeUiState
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.feature.home.HomeViewModel
import com.skeksik.current.ui.localnetwork.LocalNetworkNotice
import com.skeksik.current.ui.localnetwork.LocalNetworkNoticeKind
import com.skeksik.current.ui.navigation.AddServerRoute
import com.skeksik.current.ui.navigation.Navigator
import com.skeksik.current.ui.navigation.SettingsRoute
import com.skeksik.current.ui.navigation.ShellDestination
import com.skeksik.current.ui.navigation.ShellPage
import com.skeksik.current.ui.navigation.shellDestination
import com.skeksik.current.ui.navigation.SidebarEntry
import com.skeksik.current.ui.navigation.SignInRoute
import com.skeksik.current.ui.navigation.pageViewModel
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.home_add_server
import com.skeksik.current.ui.resources.home_no_server_message
import com.skeksik.current.ui.resources.home_no_server_title
import com.skeksik.current.ui.resources.home_open_settings
import com.skeksik.current.ui.resources.home_sign_in
import com.skeksik.current.ui.resources.home_signed_in_as
import com.skeksik.current.ui.resources.home_signed_in_message
import com.skeksik.current.ui.resources.home_signed_in_title
import com.skeksik.current.ui.resources.home_signed_out_message
import com.skeksik.current.ui.resources.home_signed_out_title
import com.skeksik.current.ui.resources.nav_home
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource
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
    shellDestination(HomeDestination)
}

@Composable
private fun HomeScreen(navigator: Navigator) {
    val viewModel = pageViewModel { HomeViewModel(get(), get()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        LocalNetworkAsk()
        HomeState(state, navigator, Modifier.weight(1f))
    }
}

/** Android only, with a saved server on the local network: asks for "Nearby devices" before that server times out (ADR 0020). */
@Composable
private fun LocalNetworkAsk() {
    val viewModel = pageViewModel { HomeLocalNetworkViewModel(get(), get()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Back from the app's settings page, the permission may have changed.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val kind = when (val shown = state) {
        HomeLocalNetworkState.Hidden -> return
        is HomeLocalNetworkState.Ask -> if (shown.denied) LocalNetworkNoticeKind.Denied else LocalNetworkNoticeKind.Explain
        HomeLocalNetworkState.Asking -> LocalNetworkNoticeKind.Explain
        HomeLocalNetworkState.Blocked -> LocalNetworkNoticeKind.Blocked
    }
    Column(
        Modifier.widthIn(max = Size.contentMaxWidth).padding(start = Spacing.xxl, top = Spacing.xxl, end = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        LocalNetworkNotice(
            kind,
            asking = state == HomeLocalNetworkState.Asking,
            onAllow = viewModel::allow,
            onOpenSettings = viewModel::openSettings,
        )
    }
}

@Composable
private fun HomeState(state: HomeUiState, navigator: Navigator, modifier: Modifier) {
    when (val shown = state) {
        HomeUiState.NoServer -> EmptyState(
            title = stringResource(Res.string.home_no_server_title),
            message = stringResource(Res.string.home_no_server_message),
            icon = CurrentIcons.Server,
            action = StateAction(stringResource(Res.string.home_add_server)) { navigator.open(AddServerRoute) },
            modifier = modifier,
        )
        is HomeUiState.SignedOut -> EmptyState(
            title = stringResource(Res.string.home_signed_out_title),
            message = stringResource(Res.string.home_signed_out_message, shown.serverName),
            icon = CurrentIcons.Server,
            action = when (shown.server.service) {
                ServiceType.jellyfin -> StateAction(stringResource(Res.string.home_sign_in)) {
                    navigator.open(SignInRoute(shown.server, shown.serverName))
                }
                else -> StateAction(stringResource(Res.string.home_open_settings)) { navigator.open(SettingsRoute) }
            },
            modifier = modifier,
        )
        is HomeUiState.SignedIn -> EmptyState(
            title = stringResource(Res.string.home_signed_in_title, shown.serverName),
            message = when (val user = shown.userName) {
                null -> stringResource(Res.string.home_signed_in_message)
                else -> stringResource(Res.string.home_signed_in_as, user, shown.serverName)
            },
            icon = CurrentIcons.Home,
            modifier = modifier,
        )
    }
}
