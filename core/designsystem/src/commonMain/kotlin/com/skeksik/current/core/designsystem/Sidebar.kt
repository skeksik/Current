package com.skeksik.current.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.skeksik.current.core.designsystem.CurrentTokens.Radius
import com.skeksik.current.core.designsystem.CurrentTokens.Size
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing

/** The app's layout on wide screens: a fixed [sidebar] on the left (with [sidebarModifier]), the current page beside it. */
@Composable
fun SidebarScaffold(
    sidebar: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    sidebarModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize()) {
            Column(
                sidebarModifier
                    .width(Size.sidebarWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                content = sidebar,
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

/** The app's name at the top of the sidebar. */
@Composable
fun SidebarTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.l).semantics { heading() },
    )
}

/**
 * The sidebar's [count] entries, one selectable group: Tab reaches them, Up and Down move between them and stop at the ends, Enter opens
 * one. [item] draws entry `index` with the modifier it must pass to its [SidebarItem].
 */
@Composable
fun SidebarItems(count: Int, item: @Composable (index: Int, modifier: Modifier) -> Unit) {
    val focus = rememberListFocus(List(count) { it })
    with(focus) {
        Column(Modifier.selectableGroup().arrowKeys(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            repeat(count) { index -> item(index, Modifier.item(index)) }
        }
    }
}

@Composable
fun SidebarItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.m)
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            // A minimum, not a height: a large system font grows the entry instead of clipping its label.
            .heightIn(min = Size.sidebarItemHeight)
            // Entries sit Spacing.xs apart and their fill is not the ring's colour, so the ring hugs the edge.
            .focusRing(shape, gap = 0.dp)
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        val tint = if (selected) colors.primary else colors.onSurfaceVariant
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Size.icon))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
    }
}
