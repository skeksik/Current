package com.skeksik.current.feature.settings

import androidx.lifecycle.ViewModel
import com.skeksik.current.core.model.AppInfo
import com.skeksik.current.core.network.PlatformInfo
import com.skeksik.current.core.network.RequestLogHistory

/**
 * Settings > About. [diagnostics] is the text "Copy diagnostics" puts on the clipboard: the app version, the platform and the request
 * log's last lines, which hold no host, header, body or secret (ADR 0015). The text is in English on purpose: it is read by whoever
 * looks into the problem, not shown in the app.
 */
class AboutViewModel(
    private val history: RequestLogHistory,
    private val platform: PlatformInfo,
) : ViewModel() {
    val version: String = AppInfo.VERSION

    fun diagnostics(): String {
        val lines = history.lines()
        return buildString {
            appendLine("${AppInfo.NAME} $version")
            appendLine("Platform: ${platform.describe()}")
            appendLine()
            appendLine(if (lines.isEmpty()) "The log is empty." else "Last ${lines.size} log lines, oldest first:")
            lines.forEach(::appendLine)
        }
    }
}
