package com.skeksik.current.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeksik.current.core.network.LocalNetworkAccess
import com.skeksik.current.core.network.LocalNetworkAskReason
import com.skeksik.current.core.network.LocalNetworkPermission
import com.skeksik.current.core.network.shouldAskForLocalNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What "Add server" shows about local network access (ADR 0020). Desktop and web never leave [Hidden]. */
sealed interface LocalNetworkPromptState {
    /** Nothing to ask: granted, or the platform does not ask. */
    data object Hidden : LocalNetworkPromptState

    /** Explain, then ask. With [denied], the user said no: local servers cannot be reached, internet addresses still work. */
    data class Ask(val denied: Boolean = false) : LocalNetworkPromptState

    /** The system dialog is up. */
    data object Asking : LocalNetworkPromptState

    /** Denied for good: only the app's settings page can allow it ("Try again" still asks, in case Android would show the dialog). */
    data object Blocked : LocalNetworkPromptState
}

/**
 * Asks for local network access when the user opens "Add server", and before the first check of an address (ADR 0020), because a
 * denied request to a local address only times out. The system dialog speaks of "nearby devices", so the screen explains first.
 */
class LocalNetworkPromptViewModel internal constructor(
    private val access: LocalNetworkAccess,
    /** For tests, which have no main dispatcher; otherwise `viewModelScope`. */
    scope: CoroutineScope?,
) : ViewModel() {
    constructor(access: LocalNetworkAccess) : this(access, scope = null)

    private val scope = scope ?: viewModelScope

    private val _state = MutableStateFlow<LocalNetworkPromptState>(LocalNetworkPromptState.Hidden)
    val state: StateFlow<LocalNetworkPromptState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Reads the permission again, for instance when the user comes back from the settings page. */
    fun refresh() {
        if (_state.value == LocalNetworkPromptState.Asking) return
        _state.value = stateFor(access.status())
    }

    /** Shows the system dialog, then runs [then] whatever the answer. */
    fun allow(then: () -> Unit = {}) {
        if (_state.value == LocalNetworkPromptState.Asking) return
        _state.value = LocalNetworkPromptState.Asking
        scope.launch {
            _state.value = stateFor(access.request(), deniedNow = true)
            then()
        }
    }

    /** Before checking an address: asks first if the user has not answered yet, otherwise checks at once. */
    fun beforeCheck(check: () -> Unit) {
        when (_state.value) {
            LocalNetworkPromptState.Ask() -> allow(then = check)
            LocalNetworkPromptState.Asking -> Unit
            else -> check()
        }
    }

    fun openSettings() = access.openSettings()

    private fun stateFor(status: LocalNetworkPermission, deniedNow: Boolean = false): LocalNetworkPromptState = when {
        shouldAskForLocalNetwork(LocalNetworkAskReason.AddServer, emptyList(), status) ->
            LocalNetworkPromptState.Ask(denied = deniedNow || (_state.value as? LocalNetworkPromptState.Ask)?.denied == true)
        status == LocalNetworkPermission.Blocked -> LocalNetworkPromptState.Blocked
        else -> LocalNetworkPromptState.Hidden
    }
}
