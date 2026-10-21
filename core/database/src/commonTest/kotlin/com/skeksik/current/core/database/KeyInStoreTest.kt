package com.skeksik.current.core.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyInStoreTest {
    private val prefixes = listOf("current.", "current.local.", "current.secret.")

    @Test
    fun aStoreSeesItsOwnKeysWithoutThePrefix() {
        assertEquals("servers", keyInStore("current.servers", "current.", prefixes))
        assertEquals("jellyfin/0a1b/token", keyInStore("current.secret.jellyfin/0a1b/token", "current.secret.", prefixes))
        assertEquals("device.id", keyInStore("current.local.device.id", "current.local.", prefixes))
    }

    @Test
    fun theSettingsStoreLeavesOutTheStoresNestedInIt() {
        assertNull(keyInStore("current.secret.jellyfin/0a1b/token", "current.", prefixes))
        assertNull(keyInStore("current.local.device.id", "current.", prefixes))
    }

    @Test
    fun otherAppsKeysOnTheOriginAreNoStoresKeys() {
        prefixes.forEach { prefix ->
            assertNull(keyInStore("theme", prefix, prefixes))
            assertNull(keyInStore("currently", prefix, prefixes))
        }
    }
}
