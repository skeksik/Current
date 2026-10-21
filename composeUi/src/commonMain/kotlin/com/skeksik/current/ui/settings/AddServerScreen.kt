package com.skeksik.current.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing
import com.skeksik.current.core.designsystem.InlineProgress
import com.skeksik.current.core.designsystem.Notice
import com.skeksik.current.core.designsystem.NoticeKind
import com.skeksik.current.core.designsystem.Page
import com.skeksik.current.core.designsystem.Panel
import com.skeksik.current.core.designsystem.focusRing
import com.skeksik.current.core.designsystem.formField
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.feature.settings.LocalNetworkPromptState
import com.skeksik.current.feature.settings.LocalNetworkPromptViewModel
import com.skeksik.current.feature.settings.ServerSetupProblem
import com.skeksik.current.feature.settings.ServerSetupUiState
import com.skeksik.current.feature.settings.ServerSetupViewModel
import com.skeksik.current.ui.navigation.Navigator
import com.skeksik.current.ui.navigation.SignInRoute
import com.skeksik.current.ui.navigation.pageViewModel
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.add_server_address
import com.skeksik.current.ui.resources.add_server_already_added
import com.skeksik.current.ui.resources.add_server_change
import com.skeksik.current.ui.resources.add_server_check
import com.skeksik.current.ui.resources.add_server_checking
import com.skeksik.current.ui.resources.add_server_confirm
import com.skeksik.current.ui.resources.add_server_different_server
import com.skeksik.current.ui.resources.add_server_found
import com.skeksik.current.ui.resources.add_server_internet
import com.skeksik.current.ui.resources.add_server_invalid
import com.skeksik.current.ui.resources.add_server_local
import com.skeksik.current.ui.resources.add_server_not_this_service
import com.skeksik.current.ui.resources.add_server_plain_http
import com.skeksik.current.ui.resources.add_server_redirected
import com.skeksik.current.ui.resources.add_server_refused
import com.skeksik.current.ui.resources.add_server_subtitle
import com.skeksik.current.ui.resources.add_server_timed_out
import com.skeksik.current.ui.resources.add_server_title
import com.skeksik.current.ui.resources.add_server_unreachable
import com.skeksik.current.ui.resources.add_server_version
import com.skeksik.current.ui.resources.local_network_ask_again
import com.skeksik.current.ui.resources.local_network_blocked
import com.skeksik.current.ui.resources.local_network_continue
import com.skeksik.current.ui.resources.local_network_denied
import com.skeksik.current.ui.resources.local_network_explain
import com.skeksik.current.ui.resources.local_network_open_settings
import com.skeksik.current.ui.resources.local_network_try_again
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Settings > Servers > Add server. Once saved, the sign-in page for the new server takes this page's place. */
@Composable
internal fun AddServerScreen(navigator: Navigator) {
    val viewModel = pageViewModel { ServerSetupViewModel(get(), get(), get()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localNetwork = pageViewModel { LocalNetworkPromptViewModel(get()) }
    val field = remember { FocusRequester() }
    val confirm = remember { FocusRequester() }
    // Asks for local network access first, if the user has not answered yet: a denied local address only times out (ADR 0020).
    val check = { if (state is ServerSetupUiState.Editing) localNetwork.beforeCheck(viewModel::check) }
    // Back from the app's settings page, the permission may have changed.
    LifecycleResumeEffect(localNetwork) {
        localNetwork.refresh()
        onPauseOrDispose {}
    }

    Page(title = stringResource(Res.string.add_server_title), subtitle = stringResource(Res.string.add_server_subtitle)) {
        LocalNetworkPrompt(localNetwork)
        OutlinedTextField(
            value = state.input,
            onValueChange = viewModel::onInput,
            label = { Text(stringResource(Res.string.add_server_address)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { check() }),
            modifier = Modifier.fillMaxWidth().focusRequester(field).formField { check() },
        )
        when (val shown = state) {
            is ServerSetupUiState.Editing -> {
                shown.problem?.let { Notice(stringResource(it.message())) }
                Button(check, Modifier.focusRing(MaterialTheme.shapes.extraLarge), enabled = shown.input.isNotBlank()) {
                    Text(stringResource(Res.string.add_server_check))
                }
            }
            is ServerSetupUiState.Checking -> InlineProgress(stringResource(Res.string.add_server_checking))
            is ServerSetupUiState.Found -> {
                Panel {
                    Text(stringResource(Res.string.add_server_found, shown.name), style = MaterialTheme.typography.titleMedium)
                    shown.version?.let { Text(stringResource(Res.string.add_server_version, it), style = MaterialTheme.typography.bodyMedium) }
                    Text(shown.address, style = MaterialTheme.typography.bodyMedium)
                    val scope = if (shown.scope == AddressScope.Local) Res.string.add_server_local else Res.string.add_server_internet
                    Notice(stringResource(scope), NoticeKind.Info)
                }
                if (shown.plainHttpOnInternet) Notice(stringResource(Res.string.add_server_plain_http), NoticeKind.Warning)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Button(viewModel::confirm, Modifier.focusRing(MaterialTheme.shapes.extraLarge).focusRequester(confirm)) {
                        Text(stringResource(Res.string.add_server_confirm))
                    }
                    OutlinedButton({ field.requestFocus() }, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                        Text(stringResource(Res.string.add_server_change))
                    }
                }
                // The user reads what was found and confirms it themselves: Enter in the field never adds a server.
                LaunchedEffect(shown) { confirm.requestFocus() }
            }
            is ServerSetupUiState.Saved -> LaunchedEffect(shown) {
                val server = shown.server
                // Only Jellyfin has a sign-in page yet; any other service goes back to where Add server was opened.
                if (server.ref.service == ServiceType.jellyfin) navigator.replace(SignInRoute(server.ref, server.name)) else navigator.back()
            }
        }
    }
    LaunchedEffect(Unit) { field.requestFocus() }
}

/** Android only: why the next dialog asks for "Nearby devices", what a denial means, and the way back once it is final. */
@Composable
private fun LocalNetworkPrompt(viewModel: LocalNetworkPromptViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val shown = state) {
        LocalNetworkPromptState.Hidden -> Unit
        is LocalNetworkPromptState.Ask, LocalNetworkPromptState.Asking -> {
            val denied = (shown as? LocalNetworkPromptState.Ask)?.denied == true
            Notice(
                stringResource(if (denied) Res.string.local_network_denied else Res.string.local_network_explain),
                if (denied) NoticeKind.Warning else NoticeKind.Info,
            )
            OutlinedButton(
                { viewModel.allow() },
                Modifier.focusRing(MaterialTheme.shapes.extraLarge),
                enabled = shown != LocalNetworkPromptState.Asking,
            ) {
                Text(stringResource(if (denied) Res.string.local_network_ask_again else Res.string.local_network_continue))
            }
        }
        LocalNetworkPromptState.Blocked -> {
            Notice(stringResource(Res.string.local_network_blocked), NoticeKind.Warning)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(viewModel::openSettings, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                    Text(stringResource(Res.string.local_network_open_settings))
                }
                OutlinedButton({ viewModel.allow() }, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                    Text(stringResource(Res.string.local_network_try_again))
                }
            }
        }
    }
}

private fun ServerSetupProblem.message(): StringResource = when (this) {
    ServerSetupProblem.InvalidAddress -> Res.string.add_server_invalid
    ServerSetupProblem.Unreachable -> Res.string.add_server_unreachable
    ServerSetupProblem.TimedOut -> Res.string.add_server_timed_out
    ServerSetupProblem.Redirected -> Res.string.add_server_redirected
    ServerSetupProblem.NotThisService -> Res.string.add_server_not_this_service
    ServerSetupProblem.Refused -> Res.string.add_server_refused
    ServerSetupProblem.AlreadyAdded -> Res.string.add_server_already_added
    ServerSetupProblem.DifferentServer -> Res.string.add_server_different_server
}
