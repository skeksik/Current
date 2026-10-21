package com.skeksik.current.core.network

import com.skeksik.current.core.database.SharedMemoryStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Two copies of the app over one store, as two browser tabs over `localStorage` (W7). */
class ServerRepositoryTabsTest {
    private val storage = SharedMemoryStore()
    private val local = ServerAddress("http://192.168.1.10:8096", AddressScope.Local)
    private val internet = ServerAddress("https://media.example.com", AddressScope.Internet)

    private fun server(name: String) =
        SavedServer(newServerRef(ServiceType.jellyfin), "0123456789abcdef0123456789abcdef", name, listOf(local))

    private class Tab(val servers: StoredServerRepository, val capabilities: MutableCapabilitiesState)

    private fun TestScope.tab(): Tab {
        val capabilities = MutableCapabilitiesState()
        return Tab(StoredServerRepository(storage.settings(), capabilities, backgroundScope), capabilities)
    }

    @Test
    fun aServerRemovedInOneTabLeavesTheOther() = runTest {
        val home = server("Home")
        val a = tab()
        a.servers.save(home)
        val b = tab()
        b.servers.remove(home.ref)
        runCurrent()
        assertEquals(emptyList(), a.servers.servers.value)
        assertNull(a.capabilities.capabilities.value.status(home.ref))
    }

    @Test
    fun aServerAddedInOneTabShowsInTheOtherSignedOut() = runTest {
        val a = tab()
        val b = tab()
        val home = server("Home")
        b.servers.save(home)
        runCurrent()
        assertEquals(listOf(home), a.servers.servers.value)
        assertNotNull(a.capabilities.capabilities.value.status(home.ref))
    }

    @Test
    fun addressesChangedInOneTabShowInTheOther() = runTest {
        val home = server("Home")
        val a = tab()
        a.servers.save(home)
        val b = tab()
        b.servers.save(home.copy(addresses = listOf(internet, local)))
        runCurrent()
        assertEquals(listOf(internet, local), a.servers.servers.value.single().addresses)
    }

    @Test
    fun aTabThatHasNotHeardOfARemovalYetDoesNotWriteItBack() = runTest {
        val home = server("Home")
        val a = tab()
        a.servers.save(home)
        val b = tab()
        b.servers.remove(home.ref)
        // Before a hears of it: a adds a server from its own, stale list.
        val other = server("Other")
        a.servers.save(other)
        runCurrent()
        assertEquals(listOf(other), a.servers.servers.value)
        assertEquals(listOf(other), b.servers.servers.value)
        assertEquals(listOf(other), StoredServerRepository(storage.settings(), MutableCapabilitiesState()).servers.value)
        assertEquals(1, Regex("\"key\"").findAll(storage.settings().get("servers")!!).count(), "no dangling ref in the index")
    }

    @Test
    fun twoTabsAddingAtOnceKeepBoth() = runTest {
        val a = tab()
        val b = tab()
        val first = server("First")
        val second = server("Second")
        a.servers.save(first)
        b.servers.save(second)
        runCurrent()
        assertEquals(listOf(first, second), a.servers.servers.value)
        assertEquals(listOf(first, second), b.servers.servers.value)
    }

    @Test
    fun aClearedStoreEmptiesEveryTab() = runTest {
        val home = server("Home")
        val a = tab()
        a.servers.save(home)
        storage.clear()
        runCurrent()
        assertEquals(emptyList(), a.servers.servers.value)
        assertNull(a.capabilities.capabilities.value.status(home.ref))
    }
}
