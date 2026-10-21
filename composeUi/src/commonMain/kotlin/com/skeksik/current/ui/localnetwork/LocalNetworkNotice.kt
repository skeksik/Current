package com.skeksik.current.ui.localnetwork

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing
import com.skeksik.current.core.designsystem.Notice
import com.skeksik.current.core.designsystem.NoticeKind
import com.skeksik.current.core.designsystem.focusRing
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.local_network_ask_again
import com.skeksik.current.ui.resources.local_network_blocked
import com.skeksik.current.ui.resources.local_network_continue
import com.skeksik.current.ui.resources.local_network_denied
import com.skeksik.current.ui.resources.local_network_explain
import com.skeksik.current.ui.resources.local_network_open_settings
import com.skeksik.current.ui.resources.local_network_try_again
import org.jetbrains.compose.resources.stringResource

/** What [LocalNetworkNotice] says (ADR 0020). */
internal enum class LocalNetworkNoticeKind {
    /** Why the next dialog asks for "Nearby devices", with Continue. */
    Explain,

    /** The user said no: what that means, with "Ask again". */
    Denied,

    /** Denied for good: the app's settings page, and "Try again". */
    Blocked,
}

/**
 * Android only: the local network notice of Add server and Home. [asking] while the system dialog is up, which disables the button
 * that opened it. [onAllow] shows the dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalNetworkNotice(kind: LocalNetworkNoticeKind, asking: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    when (kind) {
        LocalNetworkNoticeKind.Explain, LocalNetworkNoticeKind.Denied -> {
            val denied = kind == LocalNetworkNoticeKind.Denied
            Notice(
                stringResource(if (denied) Res.string.local_network_denied else Res.string.local_network_explain),
                if (denied) NoticeKind.Warning else NoticeKind.Info,
            )
            OutlinedButton(onAllow, Modifier.focusRing(MaterialTheme.shapes.extraLarge), enabled = !asking) {
                Text(stringResource(if (denied) Res.string.local_network_ask_again else Res.string.local_network_continue))
            }
        }
        LocalNetworkNoticeKind.Blocked -> {
            Notice(stringResource(Res.string.local_network_blocked), NoticeKind.Warning)
            // Wraps at phone width, where the two buttons do not fit side by side.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(onOpenSettings, Modifier.focusRing(MaterialTheme.shapes.extraLarge)) {
                    Text(stringResource(Res.string.local_network_open_settings))
                }
                OutlinedButton(onAllow, Modifier.focusRing(MaterialTheme.shapes.extraLarge), enabled = !asking) {
                    Text(stringResource(Res.string.local_network_try_again))
                }
            }
        }
    }
}
