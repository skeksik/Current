package com.skeksik.current.feature.settings

import com.skeksik.current.core.network.LocalNetworkAccess
import com.skeksik.current.core.network.LocalNetworkPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
