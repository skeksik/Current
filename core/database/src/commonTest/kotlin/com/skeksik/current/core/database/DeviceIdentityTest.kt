package com.skeksik.current.core.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceIdentityTest {
    @Test
    fun theIdIsMadeOnceAndKept() {
        val local = InMemoryKeyValueStore()
        val first = DeviceIdentity.load(local, "Desk")
        val second = DeviceIdentity.load(local, "Desk")
        assertEquals(first.deviceId, second.deviceId)
        assertTrue(first.deviceId.length == 32 && first.deviceId.all { it in "0123456789abcdef" })
    }

    @Test
    fun anotherInstallGetsAnotherId() {
        assertNotEquals(DeviceIdentity.load(InMemoryKeyValueStore(), "A").deviceId, DeviceIdentity.load(InMemoryKeyValueStore(), "B").deviceId)
    }

    @Test
    fun aBlankNameIsReplaced() {
        assertEquals("Unknown device", DeviceIdentity.load(InMemoryKeyValueStore(), " ").deviceName)
    }

    @Test
    fun browserNamesComeFromTheUserAgent() {
        val cases = mapOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36" to "Chrome on Windows",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36 Edg/150.0.0.0" to "Edge on Windows",
            "Mozilla/5.0 (X11; Linux x86_64; rv:151.0) Gecko/20100101 Firefox/151.0" to "Firefox on Linux",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15" to "Safari on macOS",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/150.0 Mobile/15E148 Safari/604.1" to "Chrome on iOS",
            "" to "Browser",
        )
        cases.forEach { (agent, name) -> assertEquals(name, browserDeviceName(agent), agent) }
    }
}
