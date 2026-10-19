package com.skeksik.current.spike.desktopvideo

import kotlin.test.Test
import kotlin.test.assertEquals

class GpuCountersTest {
    private val counters = GpuCounters()

    @Test
    fun sumsOwnProcessAndBusiestEngine() {
        val header = counters.parseCsvLine(
            "\"(PDH-CSV 4.0)\"," +
                "\"\\\\PC\\GPU Engine(pid_42_luid_0x0_0xC5E8_phys_0_eng_0_engtype_3D)\\Utilization Percentage\"," +
                "\"\\\\PC\\GPU Engine(pid_7_luid_0x0_0xC5E8_phys_0_eng_0_engtype_3D)\\Utilization Percentage\"," +
                "\"\\\\PC\\GPU Engine(pid_42_luid_0x0_0xC5E8_phys_0_eng_3_engtype_VideoDecode)\\Utilization Percentage\"",
        )
        val row = counters.parseCsvLine("\"10/18/2026 19:00:00.123\",\"12.5\",\"30.0\",\"40.25\"")
        assertEquals(4, header.size)
        assertEquals(GpuLoad(self3d = 12.5, selfDecode = 40.25, max3d = 42.5, maxDecode = 40.25), counters.summarize(header, row, pid = 42))
    }

    @Test
    fun redactsKeysAndTokens() {
        assertEquals("https://h/Videos/1/stream?static=true&ApiKey=***", redact("https://h/Videos/1/stream?static=true&ApiKey=abc123"))
        assertEquals("MediaBrowser Token=\"***\"", redact("MediaBrowser Token=\"abc\""))
    }
}
