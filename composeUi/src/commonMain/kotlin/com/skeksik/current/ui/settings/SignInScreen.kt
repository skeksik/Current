package com.skeksik.current.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing
import com.skeksik.current.core.designsystem.InlineProgress
import com.skeksik.current.core.designsystem.Notice
import com.skeksik.current.core.designsystem.Page
import com.skeksik.current.core.designsystem.Panel
import com.skeksik.current.core.designsystem.codeStyle
import com.skeksik.current.core.designsystem.focusRing
import com.skeksik.current.core.designsystem.formField
import com.skeksik.current.feature.settings.SignInProblem
import com.skeksik.current.feature.settings.SignInUiState
import com.skeksik.current.feature.settings.SignInViewModel
import com.skeksik.current.ui.navigation.Navigator
import com.skeksik.current.ui.navigation.SignInRoute
import com.skeksik.current.ui.navigation.pageViewModel
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.sign_in_different_server
import com.skeksik.current.ui.resources.sign_in_password
import com.skeksik.current.ui.resources.sign_in_quick_connect
import com.skeksik.current.ui.resources.sign_in_quick_connect_cancel
import com.skeksik.current.ui.resources.sign_in_quick_connect_disabled
import com.skeksik.current.ui.resources.sign_in_quick_connect_expired
import com.skeksik.current.ui.resources.sign_in_quick_connect_message
import com.skeksik.current.ui.resources.sign_in_quick_connect_title
import com.skeksik.current.ui.resources.sign_in_quick_connect_waiting
import com.skeksik.current.ui.resources.sign_in_refused
import com.skeksik.current.ui.resources.sign_in_server_not_saved
import com.skeksik.current.ui.resources.sign_in_submit
import com.skeksik.current.ui.resources.sign_in_subtitle
import com.skeksik.current.ui.resources.sign_in_timed_out
import com.skeksik.current.ui.resources.sign_in_title
import com.skeksik.current.ui.resources.sign_in_unreachable
import com.skeksik.current.ui.resources.sign_in_username
import com.skeksik.current.ui.resources.sign_in_working
import com.skeksik.current.ui.resources.sign_in_wrong_credentials
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Sign in to a saved Jellyfin server with a password or Quick Connect; goes back to where it was opened from once signed in. */
@Composable
internal fun SignInScreen(route: SignInRoute, navigator: Navigator) {
    val viewModel = pageViewModel { SignInViewModel(get(), route.server) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Page(title = stringResource(Res.string.sign_in_title, route.name), subtitle = stringResource(Res.string.sign_in_subtitle)) {
        when (val shown = state) {
            is SignInUiState.Form -> SignInForm(shown, viewModel)
            is SignInUiState.QuickConnect -> QuickConnectCode(shown.code, viewModel::cancelQuickConnect)
            is SignInUiState.SignedIn -> LaunchedEffect(shown) { navigator.back() }
        }
    }
}

@Composable
private fun SignInForm(form: SignInUiState.Form, viewModel: SignInViewModel) {
    val username = remember { FocusRequester() }
    val password = remember { FocusRequester() }
    OutlinedTextField(
        value = form.username,
        onValueChange = viewModel::onUsername,
        label = { Text(stringResource(Res.string.sign_in_username)) },
        singleLine = true,
        enabled = !form.working,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().focusRequester(username).formField { password.requestFocus() },
    )
    OutlinedTextField(
        value = form.password,
        onValueChange = viewModel::onPassword,
        label = { Text(stringResource(Res.string.sign_in_password)) },
        singleLine = true,
        enabled = !form.working,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().focusRequester(password).formField(viewModel::signIn),
    )
    form.problem?.let { Notice(stringResource(it.message())) }
    if (form.working) {
        InlineProgress(stringResource(Res.string.sign_in_working))
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Button(viewModel::signIn, Modifier.focusRing(MaterialTheme.shapes.extraLarge), enabled = form.username.isNotBlank()) {
                Text(stringResource(Res.string.sign_in_submit))
            }
            if (form.quickConnect) {
                OutlinedButton(viewModel::startQuickConnect, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                    Text(stringResource(Res.string.sign_in_quick_connect))
                }
            }
        }
    }
    LaunchedEffect(Unit) { username.requestFocus() }
    // Fields are disabled while signing in, which drops focus; after a failure it goes back to the password.
    LaunchedEffect(form.problem) { if (form.problem != null) password.requestFocus() }
}

@Composable
private fun QuickConnectCode(code: String, cancel: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    Panel {
        Text(stringResource(Res.string.sign_in_quick_connect_title), style = MaterialTheme.typography.titleMedium)
        Text(code, style = codeStyle, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(Res.string.sign_in_quick_connect_message), style = MaterialTheme.typography.bodyMedium)
        InlineProgress(stringResource(Res.string.sign_in_quick_connect_waiting))
    }
    OutlinedButton(cancel, Modifier.focusRing(MaterialTheme.shapes.extraLarge).focusRequester(cancelFocus)) {
        Text(stringResource(Res.string.sign_in_quick_connect_cancel))
    }
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
}

private fun SignInProblem.message(): StringResource = when (this) {
    SignInProblem.WrongCredentials -> Res.string.sign_in_wrong_credentials
    SignInProblem.Unreachable -> Res.string.sign_in_unreachable
    SignInProblem.TimedOut -> Res.string.sign_in_timed_out
    SignInProblem.Refused -> Res.string.sign_in_refused
    SignInProblem.DifferentServer -> Res.string.sign_in_different_server
    SignInProblem.QuickConnectDisabled -> Res.string.sign_in_quick_connect_disabled
    SignInProblem.QuickConnectExpired -> Res.string.sign_in_quick_connect_expired
    SignInProblem.ServerNotSaved -> Res.string.sign_in_server_not_saved
}
