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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LocalNetworkAccessTest {
    private val local = ServerAddress("http://192.168.1.10:8096", AddressScope.Local)
    private val internet = ServerAddress("https://media.example.com", AddressScope.Internet)

    private fun server(vararg addresses: ServerAddress) =
        SavedServer(newServerRef(ServiceType.jellyfin), "0123456789abcdef0123456789abcdef", "Home", addresses.toList())

    private val denied = PermissionAnswer(granted = false)
    private val deniedCanAskAgain = PermissionAnswer(granted = false, canAskAgain = true)
    private val deniedFinal = PermissionAnswer(granted = false, canAskAgain = false)

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
        assertEquals(LocalNetworkPermission.CanAsk, state.status(apiSupported = true, granted = false))
        assertEquals(LocalNetworkPermission.CanAsk, state.record(denied))
        assertEquals(LocalNetworkPermission.Blocked, state.record(denied))
        assertEquals(LocalNetworkPermission.Blocked, state.status(apiSupported = true, granted = false))
    }

    @Test
    fun aFalseAfterADenialThatCouldAskAgainIsFinal() {
        val state = LocalNetworkPermissionState(InMemoryKeyValueStore())
        assertEquals(LocalNetworkPermission.CanAsk, state.record(deniedCanAskAgain))
        assertEquals(LocalNetworkPermission.Blocked, state.record(deniedFinal))
    }

    @Test
    fun aFirstFalseIsNotFinalButTwoAre() {
        // With no earlier "can ask again", a false may be a dialog closed without an answer: the counter decides.
        val state = LocalNetworkPermissionState(InMemoryKeyValueStore())
        assertEquals(LocalNetworkPermission.CanAsk, state.record(deniedFinal))
        assertEquals(LocalNetworkPermission.Blocked, state.record(deniedFinal))
    }

    @Test
    fun aResetByThePlatformUnblocksAndTheNextDenialIsFinalAgain() {
        val state = LocalNetworkPermissionState(InMemoryKeyValueStore())
        state.record(deniedCanAskAgain)
        assertEquals(LocalNetworkPermission.Blocked, state.record(deniedFinal))
        // Android reset its denials: the dialog showed again, and after this denial it can show once more.
        assertEquals(LocalNetworkPermission.CanAsk, state.record(deniedCanAskAgain))
        assertEquals(LocalNetworkPermission.CanAsk, state.status(apiSupported = true, granted = false))
        assertEquals(LocalNetworkPermission.Blocked, state.record(deniedFinal))
    }

    @Test
    fun aBlockSurvivesARestartAndAGrantClearsEverything() {
        val store = InMemoryKeyValueStore()
        LocalNetworkPermissionState(store).apply {
            record(deniedCanAskAgain)
            record(deniedFinal)
        }
        assertEquals(LocalNetworkPermission.Blocked, LocalNetworkPermissionState(store).status(apiSupported = true, granted = false))
        // Granted in the system settings.
        assertEquals(LocalNetworkPermission.Granted, LocalNetworkPermissionState(store).status(apiSupported = true, granted = true))
        // Revoked there later: the dialog can show again, and a first false is not final.
        val state = LocalNetworkPermissionState(store)
        assertEquals(LocalNetworkPermission.CanAsk, state.status(apiSupported = true, granted = false))
        assertEquals(LocalNetworkPermission.CanAsk, state.record(deniedFinal))
    }

    @Test
    fun aGrantIsGrantedAndBelowTheApiNothingIsNeeded() {
        val state = LocalNetworkPermissionState(InMemoryKeyValueStore())
        assertEquals(LocalNetworkPermission.Granted, state.record(PermissionAnswer(granted = true)))
        assertEquals(LocalNetworkPermission.NotNeeded, state.status(apiSupported = false, granted = false))
    }

    @Test
    fun onlyNotNeededAndGrantedAllowLocalAddresses() {
        assertEquals(
            listOf(LocalNetworkPermission.NotNeeded, LocalNetworkPermission.Granted),
            LocalNetworkPermission.entries.filter { it.allowsLocal },
        )
    }

    @Test
    fun theBridgeReportsAChangeAfterEachAnswerAndEachResume() = runTest {
        val bridge = LocalNetworkRequestBridge()
        val seen = mutableListOf<Unit>()
        backgroundScope.launch { bridge.changes.collect { seen += it } }
        runCurrent()
        bridge.resumed()
        runCurrent()
        bridge.onResult(PermissionAnswer(granted = true))
        runCurrent()
        assertEquals(2, seen.size)
    }
}
