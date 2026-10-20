package com.skeksik.current.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import com.skeksik.current.data.jellyfin.JellyfinAccounts
import com.skeksik.current.data.jellyfin.SignInException
import com.skeksik.current.data.jellyfin.SignInFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings > Servers > Sign in to a saved Jellyfin server (ADR 0018). */
sealed interface SignInUiState {
    /** [quickConnect] once the server says Quick Connect is on; [working] while a password sign-in runs. */
    data class Form(
        val username: String = "",
        val password: String = "",
        val quickConnect: Boolean = false,
        val working: Boolean = false,
        val problem: SignInProblem? = null,
    ) : SignInUiState

    /** Show [code]; the user approves it in another signed-in Jellyfin app. */
    data class QuickConnect(val code: String) : SignInUiState

    data class SignedIn(val userName: String) : SignInUiState
}

enum class SignInProblem {
    WrongCredentials,
    Unreachable,
    TimedOut,

    /** Any other refusal by the server. */
    Refused,

    /** Another server answered than the one saved; nothing was kept. */
    DifferentServer,
    QuickConnectDisabled,
    QuickConnectExpired,
    ServerNotSaved,
}

class SignInViewModel internal constructor(
    private val accounts: JellyfinAccounts,
    private val server: ServerRef,
    /** For tests, which have no main dispatcher; otherwise `viewModelScope`. */
    scope: CoroutineScope?,
) : ViewModel() {
    constructor(accounts: JellyfinAccounts, server: ServerRef) : this(accounts, server, scope = null)

    private val scope = scope ?: viewModelScope

    private val _state = MutableStateFlow<SignInUiState>(SignInUiState.Form())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    private var work: Job? = null

    init {
        this.scope.launch {
            val enabled = try {
                accounts.quickConnectEnabled(server)
            } catch (e: ServiceException) {
                false
            } catch (e: SignInException) {
                false
            }
            updateForm { it.copy(quickConnect = enabled) }
        }
    }

    fun onUsername(text: String) = updateForm { it.copy(username = text, problem = null) }

    fun onPassword(text: String) = updateForm { it.copy(password = text, problem = null) }

    fun signIn() {
        val form = _state.value as? SignInUiState.Form ?: return
        if (form.working || form.username.isBlank()) return
        _state.value = form.copy(working = true, problem = null)
        work = scope.launch {
            _state.value = try {
                SignInUiState.SignedIn(accounts.signIn(server, form.username.trim(), form.password).name)
            } catch (e: ServiceException) {
                form.copy(working = false, problem = problemFor(e))
            } catch (e: SignInException) {
                form.copy(working = false, problem = problemFor(e.failure))
            }
        }
    }

    fun startQuickConnect() {
        val form = _state.value as? SignInUiState.Form ?: return
        if (form.working) return
        work?.cancel()
        work = scope.launch {
            _state.value = try {
                val request = accounts.startQuickConnect(server)
                _state.value = SignInUiState.QuickConnect(request.code)
                SignInUiState.SignedIn(accounts.finishQuickConnect(server, request).name)
            } catch (e: ServiceException) {
                form.copy(problem = problemFor(e))
            } catch (e: SignInException) {
                form.copy(problem = problemFor(e.failure))
            }
        }
    }

    /** Stops waiting for the code and goes back to the form. */
    fun cancelQuickConnect() {
        if (_state.value !is SignInUiState.QuickConnect) return
        work?.cancel()
        _state.value = SignInUiState.Form(quickConnect = true)
    }

    private fun updateForm(change: (SignInUiState.Form) -> SignInUiState.Form) =
        _state.update { if (it is SignInUiState.Form) change(it) else it }

    private fun problemFor(error: ServiceException) = when (error.kind) {
        ServiceErrorKind.Unauthorized -> SignInProblem.WrongCredentials
        ServiceErrorKind.Unreachable -> SignInProblem.Unreachable
        ServiceErrorKind.Timeout -> SignInProblem.TimedOut
        else -> SignInProblem.Refused
    }

    private fun problemFor(failure: SignInFailure) = when (failure) {
        SignInFailure.ServerNotSaved -> SignInProblem.ServerNotSaved
        SignInFailure.DifferentServer -> SignInProblem.DifferentServer
        SignInFailure.QuickConnectDisabled -> SignInProblem.QuickConnectDisabled
        SignInFailure.QuickConnectExpired -> SignInProblem.QuickConnectExpired
    }
}
