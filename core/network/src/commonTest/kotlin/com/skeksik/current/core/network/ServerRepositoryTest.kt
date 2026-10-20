package com.skeksik.current.core.network

import com.skeksik.current.core.database.InMemoryKeyValueStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRepositoryTest {
    private val store = InMemoryKeyValueStore()
    private val local = ServerAddress("http://192.168.1.10:8096", AddressScope.Local)
    private val internet = ServerAddress("https://media.example.com", AddressScope.Internet)

    private fun server(name: String = "Home", addresses: List<ServerAddress> = listOf(local)) =
        SavedServer(newServerRef(ServiceType.jellyfin), "0123456789abcdef0123456789abcdef", name, addresses)

    private fun repository(capabilities: MutableCapabilitiesState = MutableCapabilitiesState()) =
        StoredServerRepository(store, capabilities)

    @Test
    fun serversSurviveARestartInTheirOrder() {
        val first = server("First")
        val second = server("Second", listOf(internet, local))
        repository().apply {
            save(first)
            save(second)
        }
        assertEquals(listOf(first, second), repository().servers.value)
    }

    @Test
    fun savingTheSameRefReplacesIt() {
        val home = server()
        val repository = repository()
        repository.save(home)
        repository.save(home.copy(addresses = listOf(local, internet)))
        assertEquals(listOf(local, internet), repository().servers.value.single().addresses)
    }

    @Test
    fun removingForgetsTheServerAndItsEntry() {
        val home = server()
        val capabilities = MutableCapabilitiesState()
        repository(capabilities).apply {
            save(home)
            remove(home.ref)
        }
        assertEquals(emptyList(), repository().servers.value)
        assertNull(capabilities.capabilities.value.status(home.ref))
        assertTrue(store.get("servers") == "[]")
    }

    @Test
    fun savedServersAreInTheCapabilitiesSignedOut() {
        val home = server()
        repository().save(home)
        val capabilities = MutableCapabilitiesState()
        repository(capabilities)
        val status = capabilities.capabilities.value.status(home.ref)
        assertEquals(ServerStatus(home.ref), status)
        assertFalse(status!!.signedIn)
    }

    @Test
    fun anUnreadableEntryIsSkipped() {
        val home = server()
        val other = server("Other")
        repository().apply {
            save(home)
            save(other)
        }
        store.put("server.jellyfin.${home.ref.key}", "{ broken")
        assertEquals(listOf(other), repository().servers.value)
    }

    @Test
    fun newKeysAreRandomHex() {
        val keys = List(20) { newServerRef(ServiceType.jellyfin).key }
        assertTrue(keys.all { it.length == 32 && it.all { c -> c in "0123456789abcdef" } })
        assertEquals(keys.size, keys.toSet().size)
    }
}
