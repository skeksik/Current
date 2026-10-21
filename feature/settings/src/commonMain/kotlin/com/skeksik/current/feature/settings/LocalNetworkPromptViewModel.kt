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

    /**
     * Explain, then ask. With [denied], the user said no, here or on another screen ([LocalNetworkAccess.deniedBefore]): servers on
     * the local network cannot be reached, and neither can an internet name that leads there (split DNS at home); other internet
     * servers still work. The dialog then shows only when the user presses "Ask again".
     */
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

    /**
     * Reads the permission again, for instance when the user comes back from the settings page. Runs [onGranted] when that turns a
     * denial into a grant, so the page can retry a check that timed out because of it.
     */
    fun refresh(onGranted: () -> Unit = {}) {
        val before = _state.value
        if (before == LocalNetworkPromptState.Asking) return
        _state.value = stateFor(access.status())
        if (before.isDenied && _state.value == LocalNetworkPromptState.Hidden) onGranted()
    }

    /** Shows the system dialog, then runs [then] whatever the answer, and [onGranted] if it turned an earlier denial into a grant. */
    fun allow(then: () -> Unit = {}, onGranted: () -> Unit = {}) {
        val before = _state.value
        if (before == LocalNetworkPromptState.Asking) return
        _state.value = LocalNetworkPromptState.Asking
        scope.launch {
            _state.value = stateFor(access.request())
            if (before.isDenied && _state.value == LocalNetworkPromptState.Hidden) onGranted()
            then()
        }
    }

    /**
     * Before checking [input]: asks first if the user has not answered yet, otherwise checks at once (never after a denial). A blank
     * input never asks, since there is no address to reach: it is checked at once, which says it is not a server address.
     */
    fun beforeCheck(input: String, check: () -> Unit) {
        when (_state.value) {
            LocalNetworkPromptState.Ask() -> if (input.isBlank()) check() else allow(then = check)
            LocalNetworkPromptState.Asking -> Unit
            else -> check()
        }
    }

    fun openSettings() = access.openSettings()

    private fun stateFor(status: LocalNetworkPermission): LocalNetworkPromptState = when {
        shouldAskForLocalNetwork(LocalNetworkAskReason.AddServer, emptyList(), status) ->
            LocalNetworkPromptState.Ask(denied = access.deniedBefore())
        status == LocalNetworkPermission.Blocked -> LocalNetworkPromptState.Blocked
        else -> LocalNetworkPromptState.Hidden
    }
}

/**
 * Whether a check that ended in [problem] may have failed because local network access is denied: a denied local address does not fail,
 * it times out, and so does an internet name that leads to the local network. The page then points to the permission.
 */
fun LocalNetworkPromptState.mayExplain(problem: ServerSetupProblem?): Boolean = problem == ServerSetupProblem.TimedOut && isDenied

private val LocalNetworkPromptState.isDenied: Boolean
    get() = this == LocalNetworkPromptState.Blocked || (this as? LocalNetworkPromptState.Ask)?.denied == true
