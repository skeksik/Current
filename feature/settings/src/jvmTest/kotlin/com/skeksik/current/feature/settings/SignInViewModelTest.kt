package com.skeksik.current.feature.settings

import com.skeksik.current.core.database.InMemorySecretStore
import com.skeksik.current.core.database.SecretStorage
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.model.User
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerClient
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import com.skeksik.current.data.jellyfin.JellyfinAccounts
import com.skeksik.current.data.jellyfin.QuickConnectRequest
import com.skeksik.current.data.jellyfin.SignInException
import com.skeksik.current.data.jellyfin.SignInFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val home = ServerRef(ServiceType.jellyfin, "home")

/** Answers as told; records sign-outs. */
private class FakeAccounts : JellyfinAccounts {
    var quickConnect = true
    var signInResult: () -> User = { User("u1", home, "Alex") }
    val approval = CompletableDeferred<Unit>()
    var finishFailure: SignInFailure? = null
    val signedOut = mutableListOf<ServerRef>()

    override suspend fun signIn(server: ServerRef, username: String, password: String) = signInResult()
    override suspend fun quickConnectEnabled(server: ServerRef) = quickConnect
    override suspend fun startQuickConnect(server: ServerRef) = QuickConnectRequest("123456", "secret")
    override suspend fun finishQuickConnect(server: ServerRef, request: QuickConnectRequest): User {
        approval.await()
        finishFailure?.let { throw SignInException(it) }
        return User("u1", home, "Alex")
    }
    override suspend fun signOut(server: ServerRef) {
        signedOut += server
    }
    override suspend fun authorizedClient(server: ServerRef): ServerClient? = null
}

class SignInViewModelTest {
    private suspend fun SignInViewModel.settled(predicate: (SignInUiState) -> Boolean) = withTimeout(5_000) { state.first(predicate) }

    private fun CoroutineScope.viewModel(accounts: FakeAccounts) = SignInViewModel(accounts, home, scope = this)

    @Test
    fun quickConnectIsOfferedWhenTheServerHasItOn() = runBlocking<Unit> {
        val form = viewModel(FakeAccounts()).settled { it is SignInUiState.Form && it.quickConnect }
        assertIs<SignInUiState.Form>(form)
    }

    @Test
    fun aPasswordSignInEndsSignedIn() = runBlocking<Unit> {
        val viewModel = viewModel(FakeAccounts())
        viewModel.onUsername(" alex ")
        viewModel.onPassword("pw")
        viewModel.signIn()
        assertEquals(SignInUiState.SignedIn("Alex"), viewModel.settled { it is SignInUiState.SignedIn })
    }

    @Test
    fun failuresBecomeProblemsOnTheForm() = runBlocking<Unit> {
        val cases = mapOf(
            ServiceException(home, ServiceErrorKind.Unauthorized, 401) to SignInProblem.WrongCredentials,
            ServiceException(home, ServiceErrorKind.Unreachable) to SignInProblem.Unreachable,
            ServiceException(home, ServiceErrorKind.ServerError, 500) to SignInProblem.Refused,
            SignInException(SignInFailure.DifferentServer) to SignInProblem.DifferentServer,
        )
        cases.forEach { (error, problem) ->
            val viewModel = viewModel(FakeAccounts().apply { signInResult = { throw error } })
            viewModel.onUsername("alex")
            viewModel.signIn()
            val form = viewModel.settled { it is SignInUiState.Form && it.problem != null } as SignInUiState.Form
            assertEquals(problem, form.problem)
            assertEquals("alex", form.username)
        }
    }

    @Test
    fun aBlankUsernameDoesNothing() = runBlocking<Unit> {
        val viewModel = viewModel(FakeAccounts())
        viewModel.signIn()
        assertIs<SignInUiState.Form>(viewModel.state.value)
        assertTrue(!(viewModel.state.value as SignInUiState.Form).working)
    }

    @Test
    fun quickConnectShowsTheCodeUntilApproved() = runBlocking<Unit> {
        val accounts = FakeAccounts()
        val viewModel = viewModel(accounts)
        viewModel.startQuickConnect()
        assertEquals(SignInUiState.QuickConnect("123456"), viewModel.settled { it is SignInUiState.QuickConnect })
        accounts.approval.complete(Unit)
        assertEquals(SignInUiState.SignedIn("Alex"), viewModel.settled { it is SignInUiState.SignedIn })
    }

    @Test
    fun anExpiredCodeGoesBackToTheForm() = runBlocking<Unit> {
        val accounts = FakeAccounts().apply { finishFailure = SignInFailure.QuickConnectExpired }
        val viewModel = viewModel(accounts)
        viewModel.startQuickConnect()
        viewModel.settled { it is SignInUiState.QuickConnect }
        accounts.approval.complete(Unit)
        val form = viewModel.settled { it is SignInUiState.Form && it.problem != null } as SignInUiState.Form
        assertEquals(SignInProblem.QuickConnectExpired, form.problem)
    }

    @Test
    fun cancellingQuickConnectReturnsToTheForm() = runBlocking<Unit> {
        val viewModel = viewModel(FakeAccounts())
        viewModel.startQuickConnect()
        viewModel.settled { it is SignInUiState.QuickConnect }
        viewModel.cancelQuickConnect()
        assertEquals(SignInUiState.Form(quickConnect = true), viewModel.state.value)
    }
}

class ServersViewModelTest {
    private val saved = SavedServer(home, "0123456789abcdef0123456789abcdef", "Home", listOf(ServerAddress("http://192.168.1.10:8096", AddressScope.Local)))

    private class Servers(initial: List<SavedServer>) : ServerRepository {
        private val state = MutableStateFlow(initial)
        override val servers: StateFlow<List<SavedServer>> = state
        override fun save(server: SavedServer) {
            state.value = state.value.filterNot { it.ref == server.ref } + server
        }
        override fun remove(ref: ServerRef) {
            state.value = state.value.filterNot { it.ref == ref }
        }
    }

    @Test
    fun rowsShowWhoIsSignedInAndWhereTokensAre() = runBlocking<Unit> {
        val capabilities = MutableCapabilitiesState().apply { put(ServerStatus(home, signedIn = true, reachable = true, user = User("u1", home, "Alex"))) }
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val viewModel = ServersViewModel(Servers(listOf(saved)), capabilities, FakeAccounts(), InMemorySecretStore(), scope)
        val state = withTimeout(5_000) { viewModel.state.first { it.servers.isNotEmpty() } }
        assertEquals(listOf(ServerRow(home, "Home", "http://192.168.1.10:8096", "Alex", true)), state.servers)
        assertEquals(SecretStorage.Memory, state.secretStorage)
        scope.cancel()
    }

    @Test
    fun removingSignsOutFirst() = runBlocking<Unit> {
        val servers = Servers(listOf(saved))
        val accounts = FakeAccounts()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val viewModel = ServersViewModel(servers, MutableCapabilitiesState(), accounts, InMemorySecretStore(), scope)
        viewModel.remove(home)
        withTimeout(5_000) { servers.servers.first { it.isEmpty() } }
        assertEquals(listOf(home), accounts.signedOut)
        scope.cancel()
    }
}
