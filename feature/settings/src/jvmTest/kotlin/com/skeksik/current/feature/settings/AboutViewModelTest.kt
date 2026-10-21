package com.skeksik.current.feature.settings

import com.skeksik.current.core.model.AppInfo
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.PlatformInfo
import com.skeksik.current.core.network.RequestLogBuffer
import com.skeksik.current.core.network.RequestLogEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class AboutViewModelTest {
    private val platform = PlatformInfo { "Test OS 1" }

    @Test
    fun diagnosticsHaveTheVersionPlatformAndLogOldestFirst() {
        val log = RequestLogBuffer()
        val server = ServerRef(ServiceType.jellyfin, "server-1")
        log.log(RequestLogEntry(server, "GET", "/System/Info/Public", 200, 12))
        log.log(RequestLogEntry(server, "POST", "/Users/AuthenticateByName", null, 30, cancelled = true))
        log.bug("Network changes failed", IllegalStateException("not shown"))
        assertEquals(
            """
            |${AppInfo.NAME} ${AppInfo.VERSION}
            |Platform: Test OS 1
            |
            |Last 3 log lines, oldest first:
            |jellyfin/server-1 GET /System/Info/Public 200 (12 ms)
            |jellyfin/server-1 POST /Users/AuthenticateByName cancelled (30 ms)
            |bug: Network changes failed: IllegalStateException
            |
            """.trimMargin(),
            AboutViewModel(log, platform).diagnostics(),
        )
    }

    @Test
    fun anEmptyLogSaysSo() {
        val text = AboutViewModel(RequestLogBuffer(), platform).diagnostics()
        assertEquals("The log is empty.", text.lines().dropLast(1).last())
    }
}
