package com.skeksik.current.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing
import com.skeksik.current.core.designsystem.focusRing
import com.skeksik.current.feature.settings.AboutViewModel
import com.skeksik.current.ui.navigation.pageViewModel
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.settings_copy_diagnostics
import com.skeksik.current.ui.resources.settings_diagnostics_copied
import com.skeksik.current.ui.resources.settings_diagnostics_hint
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Settings > About's "Copy diagnostics": the version, the platform and the request log's last lines (ADR 0015). */
@Composable
internal fun CopyDiagnostics() {
    val viewModel = pageViewModel { AboutViewModel(get(), get()) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Text(stringResource(Res.string.settings_diagnostics_hint), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(plainTextClip(viewModel.diagnostics()))
                    copied = true
                }
            },
            modifier = Modifier.focusRing(MaterialTheme.shapes.extraLarge),
        ) {
            Text(stringResource(Res.string.settings_copy_diagnostics))
        }
        if (copied) Text(stringResource(Res.string.settings_diagnostics_copied), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A clipboard entry holding [text]; each platform builds it from its own clipboard type. */
internal expect fun plainTextClip(text: String): ClipEntry
