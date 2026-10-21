package com.skeksik.current.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing
import com.skeksik.current.core.designsystem.focusRing
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.feature.settings.RoamingNoticesViewModel
import com.skeksik.current.ui.navigation.ShellOverlay
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.roaming_dismiss
import com.skeksik.current.ui.resources.roaming_now_internet
import com.skeksik.current.ui.resources.roaming_now_local
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin

/** The roaming notice over every page, bound in [settingsUiModule]: the shell draws it without naming this feature (ADR 0014). */
internal object RoamingNoticeOverlay : ShellOverlay {
    @Composable
    override fun Content(modifier: Modifier) = RoamingNotice(modifier)
}

/**
 * A brief notice when roaming switches a server to another address (ADR 0019): the server and the network kind, never the address. It
 * clears itself after a few seconds and never takes focus, so it does not interrupt the page; a screen reader reads it out.
 */
@Composable
private fun RoamingNotice(modifier: Modifier) {
    val koin = getKoin()
    // One for the window, outside the pages: it lives as long as the shell.
    val viewModel = viewModel { RoamingNoticesViewModel(koin.get(), koin.get()) }
    val notice by viewModel.state.collectAsStateWithLifecycle()
    val shown = notice ?: return
    val text = stringResource(
        if (shown.scope == AddressScope.Local) Res.string.roaming_now_local else Res.string.roaming_now_internet,
        shown.serverName,
    )
    Surface(
        modifier.widthIn(max = 560.dp).semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.xs, bottom = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium)
            TextButton(viewModel::dismiss, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                Text(stringResource(Res.string.roaming_dismiss), color = MaterialTheme.colorScheme.inversePrimary, maxLines = 1)
            }
        }
    }
}
