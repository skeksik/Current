package com.skeksik.current.feature.settings

import com.skeksik.current.core.network.LocalNetworkAccess
import com.skeksik.current.core.network.LocalNetworkPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNetworkPromptViewModelTest {
    /** [answers] are what each request returns, in turn; [status] is what the settings page would show. */
    private class FakeAccess(var status: LocalNetworkPermission, vararg answers: LocalNetworkPermission) : LocalNetworkAccess {
        private val answers = answers.toMutableList()
        var requests = 0
        var settingsOpened = 0

        override fun status() = status

        override suspend fun request(): LocalNetworkPermission {
            requests++
            status = answers.removeAt(0)
            return status
        }

        override fun openSettings() {
            settingsOpened++
        }
    }

    // Unconfined, outside runBlocking: the fake's answers arrive before allow() returns.
    private fun viewModel(access: LocalNetworkAccess) = LocalNetworkPromptViewModel(access, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun nothingToAskStaysHidden() {
        for (status in listOf(LocalNetworkPermission.NotNeeded, LocalNetworkPermission.Granted)) {
            val access = FakeAccess(status)
            val viewModel = viewModel(access)
            assertEquals(LocalNetworkPromptState.Hidden, viewModel.state.value)
            var checked = 0
            viewModel.beforeCheck { checked++ }
            assertEquals(1, checked)
            assertEquals(0, access.requests)
        }
    }

    @Test
    fun theFirstCheckAsksThenChecks() {
        val access = FakeAccess(LocalNetworkPermission.CanAsk, LocalNetworkPermission.Granted)
        val viewModel = viewModel(access)
        assertEquals(LocalNetworkPromptState.Ask(), viewModel.state.value)
        var checked = 0
        viewModel.beforeCheck { checked++ }
        assertEquals(1, access.requests)
        assertEquals(1, checked)
        assertEquals(LocalNetworkPromptState.Hidden, viewModel.state.value)
    }

    @Test
    fun aDenialIsShownAndLaterChecksDoNotAskAgain() {
        val access = FakeAccess(LocalNetworkPermission.CanAsk, LocalNetworkPermission.CanAsk)
        val viewModel = viewModel(access)
        var checked = 0
        viewModel.beforeCheck { checked++ }
        assertEquals(LocalNetworkPromptState.Ask(denied = true), viewModel.state.value)
        viewModel.beforeCheck { checked++ }
        assertEquals(2, checked)
        assertEquals(1, access.requests)
        // Still denied after coming back to the page.
        viewModel.refresh()
        assertEquals(LocalNetworkPromptState.Ask(denied = true), viewModel.state.value)
    }

    @Test
    fun blockedOffersTheSettingsAndNoticesAGrantThere() {
        val access = FakeAccess(LocalNetworkPermission.CanAsk, LocalNetworkPermission.Blocked)
        val viewModel = viewModel(access)
        viewModel.allow()
        assertEquals(LocalNetworkPromptState.Blocked, viewModel.state.value)
        viewModel.openSettings()
        assertEquals(1, access.settingsOpened)
        access.status = LocalNetworkPermission.Granted
        viewModel.refresh()
        assertEquals(LocalNetworkPromptState.Hidden, viewModel.state.value)
    }

    @Test
    fun aSecondTapWhileTheDialogIsUpDoesNothing() {
        val gate = CompletableDeferred<LocalNetworkPermission>()
        val access = object : LocalNetworkAccess {
            var requests = 0
            override fun status() = LocalNetworkPermission.CanAsk
            override suspend fun request(): LocalNetworkPermission {
                requests++
                return gate.await()
            }
            override fun openSettings() = Unit
        }
        val viewModel = viewModel(access)
        viewModel.allow()
        assertEquals(LocalNetworkPromptState.Asking, viewModel.state.value)
        var checked = 0
        viewModel.beforeCheck { checked++ }
        viewModel.allow()
        viewModel.refresh()
        assertEquals(LocalNetworkPromptState.Asking, viewModel.state.value)
        gate.complete(LocalNetworkPermission.Granted)
        assertEquals(LocalNetworkPromptState.Hidden, viewModel.state.value)
        assertEquals(1, access.requests)
        assertEquals(0, checked)
    }

    @Test
    fun aTimeoutPointsToThePermissionOnlyWhileItIsDenied() {
        val timedOut = ServerSetupProblem.TimedOut
        assertTrue(LocalNetworkPromptState.Ask(denied = true).mayExplain(timedOut))
        assertTrue(LocalNetworkPromptState.Blocked.mayExplain(timedOut))
        assertFalse(LocalNetworkPromptState.Ask().mayExplain(timedOut))
        assertFalse(LocalNetworkPromptState.Hidden.mayExplain(timedOut))
        assertFalse(LocalNetworkPromptState.Blocked.mayExplain(ServerSetupProblem.Unreachable))
        assertFalse(LocalNetworkPromptState.Blocked.mayExplain(null))
    }

    @Test
    fun aGrantInTheSettingsRetriesOnceOnResume() {
        val access = FakeAccess(LocalNetworkPermission.CanAsk, LocalNetworkPermission.Blocked)
        val viewModel = viewModel(access)
        var retried = 0
        viewModel.refresh(onGranted = { retried++ })
        assertEquals(0, retried)
        viewModel.allow()
        viewModel.refresh(onGranted = { retried++ })
        assertEquals(0, retried)
        access.status = LocalNetworkPermission.Granted
        viewModel.refresh(onGranted = { retried++ })
        viewModel.refresh(onGranted = { retried++ })
        assertEquals(1, retried)
    }

    @Test
    fun askingAgainRetriesOnlyOnAGrant() {
        val ask = LocalNetworkPermission.CanAsk
        val access = FakeAccess(ask, ask, ask, LocalNetworkPermission.Granted)
        val viewModel = viewModel(access)
        var retried = 0
        // The first answer follows no denial, so nothing timed out because of it.
        viewModel.allow(onGranted = { retried++ })
        viewModel.allow(onGranted = { retried++ })
        assertEquals(0, retried)
        viewModel.allow(onGranted = { retried++ })
        assertEquals(1, retried)
        assertEquals(LocalNetworkPromptState.Hidden, viewModel.state.value)
    }
}
