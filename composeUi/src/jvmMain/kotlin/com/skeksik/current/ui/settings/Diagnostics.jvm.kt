package com.skeksik.current.ui.settings

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

// Desktop's ClipEntry wraps an AWT Transferable; its constructor is still experimental.
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClip(text: String): ClipEntry = ClipEntry(StringSelection(text))
