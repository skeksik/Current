package com.skeksik.current.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skeksik.current.core.designsystem.CurrentTokens.Size
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing

/** An action on a state screen: a label and what it does. */
class StateAction(val label: String, val onClick: () -> Unit)

/** Something is on its way: a spinner and, when it helps, what is being waited for. */
@Composable
fun LoadingState(message: String? = null, modifier: Modifier = Modifier) {
    CenteredState(modifier) {
        CircularProgressIndicator(Modifier.size(32.dp))
        if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

/** Something failed: what went wrong and, when trying again can help, a [retry]. */
@Composable
fun ErrorState(title: String, message: String? = null, retry: StateAction? = null, modifier: Modifier = Modifier) {
    CenteredState(modifier) {
        Icon(CurrentIcons.Alert, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        StateText(title, message)
        if (retry != null) StateButtons(retry, secondary = null)
    }
}

/** Nothing to show yet: why, and what the user can do about it. The [action] takes focus, so Enter does it. */
@Composable
fun EmptyState(
    title: String,
    message: String? = null,
    icon: ImageVector? = null,
    action: StateAction? = null,
    secondaryAction: StateAction? = null,
    modifier: Modifier = Modifier,
) {
    CenteredState(modifier) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        StateText(title, message)
        if (action != null) StateButtons(action, secondaryAction)
    }
}

@Composable
private fun CenteredState(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = Size.contentMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) { content() }
    }
}

@Composable
private fun StateText(title: String, message: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StateButtons(primary: StateAction, secondary: StateAction?) {
    val focus = remember { FocusRequester() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Button(primary.onClick, Modifier.focusRing(MaterialTheme.shapes.extraLarge).focusRequester(focus)) { Text(primary.label) }
        if (secondary != null) {
            OutlinedButton(secondary.onClick, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) { Text(secondary.label) }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** A small spinner with [message], inside a form or a list rather than in place of the page. */
@Composable
fun InlineProgress(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        CircularProgressIndicator(Modifier.size(Size.icon), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
