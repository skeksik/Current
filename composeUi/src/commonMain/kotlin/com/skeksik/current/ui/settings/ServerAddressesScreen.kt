package com.skeksik.current.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skeksik.current.core.designsystem.CurrentIcons
import com.skeksik.current.core.designsystem.CurrentTokens.Size
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing
import com.skeksik.current.core.designsystem.InlineProgress
import com.skeksik.current.core.designsystem.LocalFocusVisibility
import com.skeksik.current.core.designsystem.Notice
import com.skeksik.current.core.designsystem.NoticeKind
import com.skeksik.current.core.designsystem.Page
import com.skeksik.current.core.designsystem.Panel
import com.skeksik.current.core.designsystem.PanelTitle
import com.skeksik.current.core.designsystem.focusRing
import com.skeksik.current.core.designsystem.rememberListFocus
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.feature.settings.AddressRow
import com.skeksik.current.feature.settings.AddressesStatus
import com.skeksik.current.feature.settings.ServerAddressesUiState
import com.skeksik.current.feature.settings.ServerAddressesViewModel
import com.skeksik.current.ui.navigation.Navigator
import com.skeksik.current.ui.navigation.pageViewModel
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.add_server_plain_http
import com.skeksik.current.ui.resources.addresses_add
import com.skeksik.current.ui.resources.addresses_checking
import com.skeksik.current.ui.resources.addresses_current_title
import com.skeksik.current.ui.resources.addresses_in_use
import com.skeksik.current.ui.resources.addresses_internet
import com.skeksik.current.ui.resources.addresses_keep_one
import com.skeksik.current.ui.resources.addresses_local
import com.skeksik.current.ui.resources.addresses_move_down
import com.skeksik.current.ui.resources.addresses_move_down_description
import com.skeksik.current.ui.resources.addresses_move_up
import com.skeksik.current.ui.resources.addresses_move_up_description
import com.skeksik.current.ui.resources.addresses_refresh
import com.skeksik.current.ui.resources.addresses_refresh_description
import com.skeksik.current.ui.resources.addresses_remove_description
import com.skeksik.current.ui.resources.addresses_remove_message
import com.skeksik.current.ui.resources.addresses_remove_title
import com.skeksik.current.ui.resources.addresses_status_checking
import com.skeksik.current.ui.resources.addresses_status_reachable
import com.skeksik.current.ui.resources.addresses_subtitle
import com.skeksik.current.ui.resources.addresses_title
import com.skeksik.current.ui.resources.addresses_unreachable
import com.skeksik.current.ui.resources.settings_remove
import org.jetbrains.compose.resources.stringResource

/** Settings > Servers > Addresses: a server's addresses in priority order, the one in use, and adding, removing and reordering them. */
@Composable
internal fun ServerAddressesScreen(route: ServerAddressesRoute, navigator: Navigator) {
    val ref = route.server
    val viewModel = pageViewModel(key = ref.saveKey) { ServerAddressesViewModel(ref, get(), get(), get()) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val name = state.name.ifEmpty { route.name }
    var removing by remember { mutableStateOf<AddressRow?>(null) }
    // This page places focus only after keyboard input: after a click or a touch, focus nobody asked for would act on the next Enter.
    val focusVisibility = LocalFocusVisibility.current
    val addAddress = remember { FocusRequester() }
    val urls = state.addresses.map { it.url }
    val rowFocus = remember { mutableMapOf<String, RowFocus>() }.apply { keys.retainAll(urls.toSet()) }
    // Where focus goes once a move or a removal shows in the list: a moved row's button can be disabled at the new place, and a removed
    // row's buttons are gone.
    var pending by remember { mutableStateOf<PendingFocus?>(null) }
    LaunchedEffect(pending, urls) {
        val shown = pending ?: return@LaunchedEffect
        when (shown) {
            is PendingFocus.Moved -> {
                // Waits for the new order; where the row is now, however many moves it took.
                if (urls == shown.before) return@LaunchedEffect
                val index = urls.indexOf(shown.url)
                if (focusVisibility.keyboard && index >= 0) {
                    // The same button while it still moves the row; at either end, the other one.
                    val up = if (shown.up) index > 0 else index == urls.lastIndex
                    rowFocus[shown.url]?.let { (if (up) it.up else it.down).requestFocus() }
                }
            }
            is PendingFocus.Removed -> {
                if (shown.url in urls) return@LaunchedEffect
                // The row that took its place, or the new last one; Add address when that row has nothing to focus.
                if (focusVisibility.keyboard) {
                    val next = urls.getOrNull(minOf(shown.index, urls.lastIndex))?.let { rowFocus[it] }
                    if (next == null || !next.row.requestFocus()) addAddress.requestFocus()
                }
            }
            is PendingFocus.Kept -> if (focusVisibility.keyboard) rowFocus[shown.url]?.remove?.requestFocus()
        }
        pending = null
    }
    // Opened by keyboard, or back from Add address, focus goes to Add address.
    LaunchedEffect(Unit) { if (focusVisibility.keyboard) addAddress.requestFocus() }
    LaunchedEffect(state.saved) { if (!state.saved) navigator.back() }

    val rows = rememberListFocus(urls)
    Page(
        title = stringResource(Res.string.addresses_title),
        subtitle = stringResource(Res.string.addresses_subtitle, name),
        actions = {
            Button(
                { navigator.open(AddServerAddressRoute(ref, name)) },
                Modifier.focusRing(MaterialTheme.shapes.extraLarge).focusRequester(addAddress),
            ) {
                Icon(CurrentIcons.Add, contentDescription = null, modifier = Modifier.size(Size.icon))
                Text(stringResource(Res.string.addresses_add), Modifier.padding(start = Spacing.s), maxLines = 1, softWrap = false)
            }
        },
    ) {
        CurrentlyUsing(state, name, refresh = viewModel::refresh)
        // Up and Down move between addresses; Tab moves between an address's buttons.
        with(rows) {
            Column(Modifier.arrowKeys(), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                state.addresses.forEachIndexed { index, row ->
                    key(row.url) {
                        val focus = rowFocus.getOrPut(row.url) { RowFocus() }
                        AddressPanel(
                            row,
                            index = index,
                            count = state.addresses.size,
                            focus = focus,
                            moveUp = {
                                pending = PendingFocus.Moved(row.url, up = true, before = viewModel.state.value.addresses.map { it.url })
                                viewModel.moveUp(row.url)
                            },
                            moveDown = {
                                pending = PendingFocus.Moved(row.url, up = false, before = viewModel.state.value.addresses.map { it.url })
                                viewModel.moveDown(row.url)
                            },
                            remove = { removing = row },
                            modifier = Modifier.item(row.url).focusRequester(focus.row),
                        )
                    }
                }
            }
        }
    }

    removing?.let { row ->
        ConfirmRemoveDialog(
            title = stringResource(Res.string.addresses_remove_title, row.url),
            message = stringResource(Res.string.addresses_remove_message),
            confirm = {
                pending = PendingFocus.Removed(row.url, state.addresses.indexOfFirst { it.url == row.url })
                viewModel.remove(row.url)
                removing = null
            },
            dismiss = {
                pending = PendingFocus.Kept(row.url)
                removing = null
            },
        )
    }
}

/** The server's status, the address in use and its network kind, and Refresh, which asks every address again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentlyUsing(state: ServerAddressesUiState, name: String, refresh: () -> Unit) {
    Panel {
        PanelTitle(stringResource(Res.string.addresses_current_title))
        // Read out when it changes, as a check ends or roaming moves to another address.
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            when (state.status) {
                AddressesStatus.Checking -> InlineProgress(stringResource(Res.string.addresses_status_checking))
                AddressesStatus.Offline -> Notice(stringResource(Res.string.addresses_unreachable, name), NoticeKind.Warning)
                AddressesStatus.Reachable -> {
                    state.inUse?.let { Text(it.url, style = MaterialTheme.typography.titleMedium) }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        state.inUse?.let {
                            val scope = if (it.scope == AddressScope.Local) Res.string.addresses_local else Res.string.addresses_internet
                            Text(
                                stringResource(scope),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(Res.string.addresses_status_reachable),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            val refreshLabel = stringResource(Res.string.addresses_refresh_description, name)
            // Disabled while a check runs, which answers it anyway.
            OutlinedButton(
                refresh,
                Modifier.focusRing(MaterialTheme.shapes.extraLarge).semantics { contentDescription = refreshLabel },
                enabled = !state.checking,
            ) {
                Text(stringResource(Res.string.addresses_refresh), maxLines = 1, softWrap = false)
            }
            // While the status is already known, a check shows here rather than replacing it.
            if (state.checking && state.status != AddressesStatus.Checking) InlineProgress(stringResource(Res.string.addresses_checking))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddressPanel(
    row: AddressRow,
    index: Int,
    count: Int,
    focus: RowFocus,
    moveUp: () -> Unit,
    moveDown: () -> Unit,
    remove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier) {
        PanelTitle(row.url)
        // Wraps when narrow, so "In use" moves below the scope rather than squeezing.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val scope = if (row.scope == AddressScope.Local) Res.string.addresses_local else Res.string.addresses_internet
            Text(stringResource(scope), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.inUse) {
                Text(
                    stringResource(Res.string.addresses_in_use),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (row.plainHttpOnInternet) Notice(stringResource(Res.string.add_server_plain_http), NoticeKind.Warning)
        if (!row.canRemove) Notice(stringResource(Res.string.addresses_keep_one), NoticeKind.Info)
        // A single address has nothing to move or remove.
        if (count > 1) {
            // Whole buttons wrap at phone width, where the three do not fit side by side; their labels never break.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                // The buttons say which address they act on, as a screen reader reads them apart from the row.
                val moveUpLabel = stringResource(Res.string.addresses_move_up_description, row.url)
                val moveDownLabel = stringResource(Res.string.addresses_move_down_description, row.url)
                val removeLabel = stringResource(Res.string.addresses_remove_description, row.url)
                OutlinedButton(
                    moveUp,
                    Modifier.focusRing(MaterialTheme.shapes.extraLarge)
                        .focusRequester(focus.up)
                        .semantics { contentDescription = moveUpLabel },
                    enabled = index > 0,
                ) {
                    Text(stringResource(Res.string.addresses_move_up), maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    moveDown,
                    Modifier.focusRing(MaterialTheme.shapes.extraLarge)
                        .focusRequester(focus.down)
                        .semantics { contentDescription = moveDownLabel },
                    enabled = index < count - 1,
                ) {
                    Text(stringResource(Res.string.addresses_move_down), maxLines = 1, softWrap = false)
                }
                if (row.canRemove) {
                    TextButton(
                        remove,
                        Modifier.focusRing(MaterialTheme.shapes.extraLarge)
                            .focusRequester(focus.remove)
                            .semantics { contentDescription = removeLabel },
                    ) {
                        Text(stringResource(Res.string.settings_remove), color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** One address's focus targets; [row] focuses its first enabled button. */
private class RowFocus {
    val row = FocusRequester()
    val up = FocusRequester()
    val down = FocusRequester()
    val remove = FocusRequester()
}

private sealed interface PendingFocus {
    /** [url] was moved by its Move up button ([up]) or Move down, from the order [before]. */
    data class Moved(val url: String, val up: Boolean, val before: List<String>) : PendingFocus

    /** [url] was removed from index [index]. */
    data class Removed(val url: String, val index: Int) : PendingFocus

    /** Removing [url] was cancelled; focus goes back to its Remove button. */
    data class Kept(val url: String) : PendingFocus
}
