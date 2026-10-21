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
    fun aServerReachedOnlyThroughAnOverlayVpnIsNotAskedAbout() {
        // Local by scope, but the permission most likely does not cover the VPN: roaming probes it anyway (ADR 0019, ADR 0020).
        val ask = { servers: List<SavedServer> ->
            shouldAskForLocalNetwork(LocalNetworkAskReason.SavedServers, servers, LocalNetworkPermission.CanAsk)
        }
        val tsNet = ServerAddress("https://media.example.ts.net", AddressScope.Local)
        val cgnat = ServerAddress("http://100.100.1.2:8096", AddressScope.Local)
        val tailscaleV6 = ServerAddress("http://[fd7a:115c:a1e0::1]:8096", AddressScope.Local)
        assertFalse(ask(listOf(server(tsNet))))
        assertFalse(ask(listOf(server(tsNet, cgnat, tailscaleV6), server(internet))))
        assertTrue(ask(listOf(server(tsNet, local))))
    }

    @Test
    fun theScopeAsSavedDoesNotDecide() {
        // An address saved under older rules: its URL decides, as roaming's does.
        val stale = ServerAddress("https://media.example.ts.net", AddressScope.Internet)
        assertFalse(server(stale).needsLocalNetworkAccess)
        assertTrue(server(ServerAddress("http://192.168.1.10:8096", AddressScope.Internet)).needsLocalNetworkAccess)
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
    fun aDenialIsRememberedForEveryScreenUntilAGrant() = runTest {
        val store = InMemoryKeyValueStore()
        assertFalse(LocalNetworkPermissionState(store).deniedBefore(apiSupported = true, granted = false))
        // A request no screen could show records nothing.
        LocalNetworkPermissionState(store).request(apiSupported = true, granted = false, LocalNetworkRequester { null })
        assertFalse(LocalNetworkPermissionState(store).deniedBefore(apiSupported = true, granted = false))
        LocalNetworkPermissionState(store).record(deniedCanAskAgain)
        // Another screen, or the next start, reads the same store.
        assertTrue(LocalNetworkPermissionState(store).deniedBefore(apiSupported = true, granted = false))
        // A dialog closed without a signal counts too.
        val counted = InMemoryKeyValueStore()
        LocalNetworkPermissionState(counted).record(denied)
        assertTrue(LocalNetworkPermissionState(counted).deniedBefore(apiSupported = true, granted = false))
        // Granted in the settings, then revoked there: the old denial is gone.
        assertFalse(LocalNetworkPermissionState(store).deniedBefore(apiSupported = true, granted = true))
        assertFalse(LocalNetworkPermissionState(store).deniedBefore(apiSupported = true, granted = false))
        assertFalse(LocalNetworkPermissionState(store).deniedBefore(apiSupported = false, granted = false))
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
