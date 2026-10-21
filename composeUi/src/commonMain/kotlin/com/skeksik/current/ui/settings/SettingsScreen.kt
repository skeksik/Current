package com.skeksik.current.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeksik.current.core.database.SecretStorage
import com.skeksik.current.core.designsystem.CurrentIcons
import com.skeksik.current.core.designsystem.CurrentTokens.Size
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing
import com.skeksik.current.core.designsystem.LocalFocusVisibility
import com.skeksik.current.core.designsystem.Notice
import com.skeksik.current.core.designsystem.NoticeKind
import com.skeksik.current.core.designsystem.Page
import com.skeksik.current.core.designsystem.Panel
import com.skeksik.current.core.designsystem.SectionTitle
import com.skeksik.current.core.designsystem.rememberListFocus
import com.skeksik.current.core.designsystem.focusRing
import com.skeksik.current.core.model.AppInfo
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.feature.settings.ServerRow
import com.skeksik.current.feature.settings.ServersViewModel
import com.skeksik.current.ui.navigation.AddServerRoute
import com.skeksik.current.ui.navigation.Navigator
import com.skeksik.current.ui.navigation.SignInRoute
import com.skeksik.current.ui.navigation.pageViewModel
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.app_name
import com.skeksik.current.ui.resources.settings_about
import com.skeksik.current.ui.resources.settings_add_server
import com.skeksik.current.ui.resources.settings_addresses
import com.skeksik.current.ui.resources.settings_no_servers
import com.skeksik.current.ui.resources.settings_offline
import com.skeksik.current.ui.resources.settings_remove
import com.skeksik.current.ui.resources.settings_remove_message
import com.skeksik.current.ui.resources.settings_remove_title
import com.skeksik.current.ui.resources.settings_secrets_browser
import com.skeksik.current.ui.resources.settings_secrets_file
import com.skeksik.current.ui.resources.settings_secrets_keep_in_file
import com.skeksik.current.ui.resources.settings_secrets_credential_manager
import com.skeksik.current.ui.resources.settings_secrets_secret_service
import com.skeksik.current.ui.resources.settings_secrets_memory
import com.skeksik.current.ui.resources.settings_servers
import com.skeksik.current.ui.resources.settings_sign_in
import com.skeksik.current.ui.resources.settings_sign_out
import com.skeksik.current.ui.resources.settings_signed_in_as
import com.skeksik.current.ui.resources.settings_signed_out
import com.skeksik.current.ui.resources.settings_title
import com.skeksik.current.ui.resources.settings_version
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsScreen(navigator: Navigator) {
    val viewModel = pageViewModel { ServersViewModel(get(), get(), get(), get()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<ServerRow?>(null) }
    val addServer = remember { FocusRequester() }
    // This page places focus only after keyboard input: after a click or a touch, focus nobody asked for would act on the next Enter.
    val focusVisibility = LocalFocusVisibility.current
    // After a removal the removed row's buttons are gone; focus goes to Add server rather than nowhere.
    var focusAddServer by remember { mutableStateOf(false) }
    LaunchedEffect(focusAddServer) {
        if (focusAddServer) {
            if (focusVisibility.keyboard) addServer.requestFocus()
            focusAddServer = false
        }
    }
    val rows = rememberListFocus(state.servers.map { it.ref })
    // What opened the page shown above this one, where focus goes back on return: the server whose Sign in button opened the sign-in
    // page (by then Sign out), the server whose Addresses button opened its addresses, or Add server. Saved, because this page leaves the composition while the other is shown.
    var signInOpener by rememberSaveable { mutableStateOf<String?>(null) }
    var addressesOpener by rememberSaveable { mutableStateOf<String?>(null) }
    var addServerOpened by rememberSaveable { mutableStateOf(false) }
    val signInButton = remember { FocusRequester() }
    val addressesButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (focusVisibility.keyboard) {
            // The server may have been removed meanwhile (from another Settings page in the history); then Add server.
            fun listed(key: String?) = key != null && state.servers.any { it.ref.saveKey == key }
            when {
                listed(signInOpener) -> signInButton.requestFocus()
                listed(addressesOpener) -> addressesButton.requestFocus()
                signInOpener != null || addressesOpener != null || addServerOpened -> addServer.requestFocus()
            }
        }
        signInOpener = null
        addressesOpener = null
        addServerOpened = false
    }

    Page(title = stringResource(Res.string.settings_title)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionTitle(stringResource(Res.string.settings_servers)) }
            Button({
                addServerOpened = true
                navigator.open(AddServerRoute)
            }, Modifier.focusRing(MaterialTheme.shapes.extraLarge).focusRequester(addServer)) {
                Icon(CurrentIcons.Add, contentDescription = null, modifier = Modifier.size(Size.icon))
                Text(stringResource(Res.string.settings_add_server), Modifier.padding(start = Spacing.s))
            }
        }
        if (state.servers.isEmpty()) {
            Notice(stringResource(Res.string.settings_no_servers), NoticeKind.Info)
        }
        // Up and Down move between servers; Tab moves between a server's buttons.
        with(rows) {
            Column(Modifier.arrowKeys(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                state.servers.forEach { row ->
                    key(row.ref) {
                        ServerPanel(
                            row,
                            signIn = {
                                signInOpener = row.ref.saveKey
                                navigator.open(SignInRoute(row.ref, row.name))
                            },
                            signOut = { viewModel.signOut(row.ref) },
                            addresses = {
                                addressesOpener = row.ref.saveKey
                                navigator.open(ServerAddressesRoute(row.ref, row.name))
                            },
                            remove = { removing = row },
                            modifier = Modifier.item(row.ref),
                            signInModifier = if (row.ref.saveKey == signInOpener) Modifier.focusRequester(signInButton) else Modifier,
                            addressesModifier = if (row.ref.saveKey == addressesOpener) Modifier.focusRequester(addressesButton) else Modifier,
                        )
                    }
                }
            }
        }
        Notice(stringResource(state.secretStorage.description()), NoticeKind.Info)
        if (state.fileOffered) {
            OutlinedButton(viewModel::keepSecretsInFile, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                Text(stringResource(Res.string.settings_secrets_keep_in_file))
            }
        }

        SectionTitle(stringResource(Res.string.settings_about))
        Panel {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.settings_version, AppInfo.VERSION), style = MaterialTheme.typography.bodyMedium)
        }
    }

    removing?.let { row ->
        ConfirmRemoveDialog(
            title = stringResource(Res.string.settings_remove_title, row.name),
            message = stringResource(Res.string.settings_remove_message),
            confirm = {
                viewModel.remove(row.ref)
                removing = null
                focusAddServer = true
            },
            dismiss = { removing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerPanel(
    row: ServerRow,
    signIn: () -> Unit,
    signOut: () -> Unit,
    addresses: () -> Unit,
    remove: () -> Unit,
    modifier: Modifier = Modifier,
    signInModifier: Modifier = Modifier,
    addressesModifier: Modifier = Modifier,
) {
    Panel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(row.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val status = row.signedInAs?.let { stringResource(Res.string.settings_signed_in_as, it) }
                ?: stringResource(Res.string.settings_signed_out)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (row.reachable == false) Notice(stringResource(Res.string.settings_offline), NoticeKind.Warning)
        }
        // Under the details, and wrapping at phone width, where the buttons do not fit beside them.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            // One button for both, so focus stays on it when signing out turns it into Sign in.
            val signedIn = row.signedInAs != null
            if (signedIn || row.ref.service == ServiceType.jellyfin) {
                OutlinedButton(
                    if (signedIn) signOut else signIn,
                    Modifier.focusRing(MaterialTheme.shapes.extraLarge).then(signInModifier),
                ) {
                    Text(stringResource(if (signedIn) Res.string.settings_sign_out else Res.string.settings_sign_in))
                }
            }
            OutlinedButton(addresses, Modifier.focusRing(MaterialTheme.shapes.extraLarge).then(addressesModifier)) {
                Text(stringResource(Res.string.settings_addresses))
            }
            TextButton(remove, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                Text(stringResource(Res.string.settings_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** A server's key in saved state and ViewModel keys. */
internal val ServerRef.saveKey get() = "${service.value}/$key"

private fun SecretStorage.description() = when (this) {
    SecretStorage.CredentialManager -> Res.string.settings_secrets_credential_manager
    SecretStorage.SecretService -> Res.string.settings_secrets_secret_service
    SecretStorage.Browser -> Res.string.settings_secrets_browser
    SecretStorage.File -> Res.string.settings_secrets_file
    SecretStorage.Memory -> Res.string.settings_secrets_memory
}
