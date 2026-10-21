package com.skeksik.current.core.network

import com.skeksik.current.core.database.InMemoryKeyValueStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNetworkAccessTest {
    private val local = ServerAddress("http://192.168.1.10:8096", AddressScope.Local)
    private val internet = ServerAddress("https://media.example.com", AddressScope.Internet)

    private fun server(vararg addresses: ServerAddress) =
        SavedServer(newServerRef(ServiceType.jellyfin), "0123456789abcdef0123456789abcdef", "Home", addresses.toList())

    @Test
    fun addServerAsksWhileTheDialogCanShow() {
        assertTrue(shouldAskForLocalNetwork(LocalNetworkAskReason.AddServer, emptyList(), LocalNetworkPermission.CanAsk))
        LocalNetworkPermission.entries.filter { it != LocalNetworkPermission.CanAsk }.forEach { status ->
            assertFalse(shouldAskForLocalNetwork(LocalNetworkAskReason.AddServer, emptyList(), status), "$status")
        }
    }

    @Test
    fun savedServersAskOnlyWhenOneHasALocalAddress() {
        val ask = { servers: List<SavedServer> ->
            shouldAskForLocalNetwork(LocalNetworkAskReason.SavedServers, servers, LocalNetworkPermission.CanAsk)
        }
        assertFalse(ask(emptyList()))
        assertFalse(ask(listOf(server(internet))))
        assertTrue(ask(listOf(server(internet), server(internet, local))))
        assertFalse(shouldAskForLocalNetwork(LocalNetworkAskReason.SavedServers, listOf(server(local)), LocalNetworkPermission.Blocked))
    }

    @Test
    fun withoutASignalTheSecondDenialIsFinal() {
        val state = LocalNetworkPermissionState(InMemoryKeyValueStore())
        assertEquals(LocalNetworkPermission.CanAsk, state.status(granted = false))
        assertEquals(LocalNetworkPermission.CanAsk, state.record(PermissionAnswer(granted = false)))
        assertEquals(LocalNetworkPermission.Blocked, state.record(PermissionAnswer(granted = false)))
        assertEquals(LocalNetworkPermission.Blocked, state.status(granted = false))
    }

    @Test
    fun theSignalDecidesFromTheSecondDenial() {
        val state = LocalNetworkPermissionState(InMemoryKeyValueStore())
        // A first "false" can be a dismissed dialog: Android makes a denial permanent only on the second.
        assertEquals(LocalNetworkPermission.CanAsk, state.record(PermissionAnswer(granted = false, canAskAgain = false)))
        assertEquals(LocalNetworkPermission.CanAsk, state.record(PermissionAnswer(granted = false, canAskAgain = true)))
        assertEquals(LocalNetworkPermission.Blocked, state.record(PermissionAnswer(granted = false, canAskAgain = false)))
        // The platform reset its denials: the dialog can show again.
        assertEquals(LocalNetworkPermission.CanAsk, state.record(PermissionAnswer(granted = false, canAskAgain = true)))
        assertEquals(LocalNetworkPermission.CanAsk, state.status(granted = false))
    }

    @Test
    fun aBlockSurvivesARestartAndAGrantClearsIt() {
        val store = InMemoryKeyValueStore()
        LocalNetworkPermissionState(store).apply {
            record(PermissionAnswer(granted = false))
            record(PermissionAnswer(granted = false))
        }
        assertEquals(LocalNetworkPermission.Blocked, LocalNetworkPermissionState(store).status(granted = false))
        // Granted in the system settings.
        assertEquals(LocalNetworkPermission.Granted, LocalNetworkPermissionState(store).status(granted = true))
        // Revoked there later: the dialog can show again, and the count starts over.
        val state = LocalNetworkPermissionState(store)
        assertEquals(LocalNetworkPermission.CanAsk, state.status(granted = false))
        assertEquals(LocalNetworkPermission.CanAsk, state.record(PermissionAnswer(granted = false)))
    }

    @Test
    fun aGrantIsGranted() {
        val state = LocalNetworkPermissionState(InMemoryKeyValueStore())
        assertEquals(LocalNetworkPermission.Granted, state.record(PermissionAnswer(granted = true)))
    }
}
