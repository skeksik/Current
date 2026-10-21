package com.skeksik.current.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skeksik.current.core.designsystem.CurrentTokens.Size
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing

/**
 * What a page shows above its title, set by the shell: a Back button on pages below a sidebar entry's first. It scrolls with the page,
 * so nothing the page scrolls slides under it.
 */
val LocalPageTop = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/**
 * A scrolling page with a [title], an optional [subtitle] and its content in a readable column. [actions] sit beside the title, and
 * [LocalPageTop] above it.
 */
@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopStart) {
        Column(
            Modifier.widthIn(max = Size.contentMaxWidth).padding(Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            LocalPageTop.current?.invoke()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                actions()
            }
            content()
        }
    }
}

/** A heading inside a page. */
@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.s))
}

/** A raised block that groups related content, such as one saved server. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = LocalPanelEdge.current?.let { BorderStroke(1.dp, it) },
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s), content = content)
    }
}

/** A message under a form: an error in the error colour, a warning in the warning colour. */
@Composable
fun Notice(text: String, kind: NoticeKind = NoticeKind.Error) {
    val color = when (kind) {
        NoticeKind.Error -> MaterialTheme.colorScheme.error
        NoticeKind.Warning -> MaterialTheme.colorScheme.tertiary
        NoticeKind.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

enum class NoticeKind { Error, Warning, Info }
