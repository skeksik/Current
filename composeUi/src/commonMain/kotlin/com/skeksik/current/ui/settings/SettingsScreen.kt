package com.skeksik.current.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.skeksik.current.core.designsystem.Notice
import com.skeksik.current.core.designsystem.NoticeKind
import com.skeksik.current.core.designsystem.Page
import com.skeksik.current.core.designsystem.Panel
import com.skeksik.current.core.designsystem.SectionTitle
import com.skeksik.current.core.designsystem.arrowKeyFocus
import com.skeksik.current.core.designsystem.focusRing
import com.skeksik.current.core.model.AppInfo
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
import com.skeksik.current.ui.resources.settings_cancel
import com.skeksik.current.ui.resources.settings_no_servers
import com.skeksik.current.ui.resources.settings_offline
import com.skeksik.current.ui.resources.settings_remove
import com.skeksik.current.ui.resources.settings_remove_message
import com.skeksik.current.ui.resources.settings_remove_title
import com.skeksik.current.ui.resources.settings_secrets_browser
import com.skeksik.current.ui.resources.settings_secrets_file
import com.skeksik.current.ui.resources.settings_secrets_keep_in_file
import com.skeksik.current.ui.resources.settings_secrets_keyring
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

    Page(title = stringResource(Res.string.settings_title)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionTitle(stringResource(Res.string.settings_servers)) }
            Button({ navigator.open(AddServerRoute) }, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                Icon(CurrentIcons.Add, contentDescription = null, modifier = Modifier.size(Size.icon))
                Text(stringResource(Res.string.settings_add_server), Modifier.padding(start = Spacing.s))
            }
        }
        if (state.servers.isEmpty()) {
            Notice(stringResource(Res.string.settings_no_servers), NoticeKind.Info)
        }
        Column(Modifier.arrowKeyFocus(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            state.servers.forEach { row ->
                ServerPanel(
                    row,
                    signIn = { navigator.open(SignInRoute(row.ref, row.name)) },
                    signOut = { viewModel.signOut(row.ref) },
                    remove = { removing = row },
                )
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
        RemoveDialog(
            row,
            confirm = {
                viewModel.remove(row.ref)
                removing = null
            },
            dismiss = { removing = null },
        )
    }
}

@Composable
private fun ServerPanel(row: ServerRow, signIn: () -> Unit, signOut: () -> Unit, remove: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(row.name, style = MaterialTheme.typography.titleMedium)
                Text(row.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val status = row.signedInAs?.let { stringResource(Res.string.settings_signed_in_as, it) }
                    ?: stringResource(Res.string.settings_signed_out)
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (row.reachable == false) Notice(stringResource(Res.string.settings_offline), NoticeKind.Warning)
            }
            // One button for both, so focus stays on it when signing out turns it into Sign in.
            val signedIn = row.signedInAs != null
            if (signedIn || row.ref.service == ServiceType.jellyfin) {
                OutlinedButton(if (signedIn) signOut else signIn, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                    Text(stringResource(if (signedIn) Res.string.settings_sign_out else Res.string.settings_sign_in))
                }
            }
            TextButton(remove, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                Text(stringResource(Res.string.settings_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Asks before removing; Cancel has focus, so Enter alone never removes a server. Escape cancels. */
@Composable
private fun RemoveDialog(row: ServerRow, confirm: () -> Unit, dismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(Res.string.settings_remove_title, row.name)) },
        text = { Text(stringResource(Res.string.settings_remove_message)) },
        confirmButton = {
            TextButton(confirm, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                Text(stringResource(Res.string.settings_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(dismiss, Modifier.focusRing(MaterialTheme.shapes.extraLarge).focusRequester(cancel)) {
                Text(stringResource(Res.string.settings_cancel))
            }
        },
    )
    LaunchedEffect(Unit) { cancel.requestFocus() }
}

private fun SecretStorage.description() = when (this) {
    SecretStorage.Keyring -> Res.string.settings_secrets_keyring
    SecretStorage.Browser -> Res.string.settings_secrets_browser
    SecretStorage.File -> Res.string.settings_secrets_file
    SecretStorage.Memory -> Res.string.settings_secrets_memory
}
