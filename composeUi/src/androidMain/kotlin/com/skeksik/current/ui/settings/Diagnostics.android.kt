package com.skeksik.current.ui.settings

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun plainTextClip(text: String): ClipEntry = ClipEntry(ClipData.newPlainText("Current diagnostics", text))
