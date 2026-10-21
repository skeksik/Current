package com.skeksik.current.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.skeksik.current.core.designsystem.focusRing
import com.skeksik.current.core.designsystem.trackFocusVisibility
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.settings_cancel
import com.skeksik.current.ui.resources.settings_remove
import org.jetbrains.compose.resources.stringResource

/** Asks before removing a server or an address; Cancel has focus, so Enter alone never removes anything. Escape cancels. */
@Composable
internal fun ConfirmRemoveDialog(title: String, message: String, confirm: () -> Unit, dismiss: () -> Unit) {
    val cancel = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = dismiss,
        // The dialog gets its keys and clicks apart from the window, so it tells the focus rings itself.
        modifier = Modifier.trackFocusVisibility(),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(confirm, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                Text(stringResource(Res.string.settings_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(dismiss, Modifier.focusRing(MaterialTheme.shapes.extraLarge).focusRequester(cancel)) {
                Text(stringResource(Res.string.settings_cancel))
            }
            // Inside the dialog's own composition, so the button is attached when focus is requested.
            LaunchedEffect(Unit) { cancel.requestFocus() }
        },
    )
}
