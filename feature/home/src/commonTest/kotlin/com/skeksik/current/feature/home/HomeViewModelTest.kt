package com.skeksik.current.feature.home

import com.skeksik.current.core.database.InMemoryKeyValueStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.model.User
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.StoredServerRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val home = ServerRef(ServiceType.jellyfin, "home")
private val cabin = ServerRef(ServiceType.jellyfin, "cabin")
private val scenes = ServerRef(ServiceType.stash, "scenes")

private fun saved(ref: ServerRef, name: String) =
    SavedServer(ref, "id-${ref.key}", name, listOf(ServerAddress("http://media.lan:8096", AddressScope.Local)))

class HomeViewModelTest {
    private val capabilities = MutableCapabilitiesState()
    private val servers = StoredServerRepository(InMemoryKeyValueStore(), capabilities)

    private fun TestScope.viewModel() = HomeViewModel(servers, capabilities, scope = backgroundScope)

    /** The state once every change so far has reached it. */
    private fun TestScope.shown(viewModel: HomeViewModel): HomeUiState {
        runCurrent()
        return viewModel.state.value
    }

    private fun signIn(ref: ServerRef, name: String) =
        capabilities.update(ref) { it.copy(signedIn = true, user = User("u-${ref.key}", ref, name)) }

    @Test
    fun withoutServersItOffersToAddOne() = runTest {
        val viewModel = viewModel()
        assertEquals(HomeUiState.NoServer, viewModel.state.value)
    }

    @Test
    fun aRestoredSessionIsShownFromTheFirstValue() = runTest {
        servers.save(saved(home, "Home"))
        signIn(home, "Alex")
        val viewModel = viewModel()
        assertEquals(HomeUiState.SignedIn("Alex", "Home"), viewModel.state.value)
    }

    @Test
    fun aSavedServerWithoutSessionOffersToSignInToTheFirstServer() = runTest {
        val viewModel = viewModel()
        servers.save(saved(home, "Home"))
        servers.save(saved(cabin, "Cabin"))
        assertEquals(HomeUiState.SignedOut(home, "Home"), shown(viewModel))
    }

    @Test
    fun signInIsOfferedForJellyfinEvenWhenAnotherServiceWasSavedFirst() = runTest {
        servers.save(saved(scenes, "Scenes"))
        servers.save(saved(home, "Home"))
        assertEquals(HomeUiState.SignedOut(home, "Home"), viewModel().state.value)
    }

    @Test
    fun withOnlyAnotherServiceSavedThatServerIsShown() = runTest {
        servers.save(saved(scenes, "Scenes"))
        assertEquals(HomeUiState.SignedOut(scenes, "Scenes"), viewModel().state.value)
    }

    @Test
    fun aServiceWithoutUsersIsSignedInWithoutAName() = runTest {
        servers.save(saved(scenes, "Scenes"))
        capabilities.update(scenes) { it.copy(signedIn = true) }
        assertEquals(HomeUiState.SignedIn(null, "Scenes"), viewModel().state.value)
    }

    @Test
    fun itFollowsSignInAndSignOutOnAnyServer() = runTest {
        servers.save(saved(home, "Home"))
        servers.save(saved(cabin, "Cabin"))
        val viewModel = viewModel()
        signIn(cabin, "Sam")
        assertEquals(HomeUiState.SignedIn("Sam", "Cabin"), shown(viewModel))
        capabilities.update(cabin) { it.copy(signedIn = false, user = null) }
        assertEquals(HomeUiState.SignedOut(home, "Home"), shown(viewModel))
        servers.remove(home)
        servers.remove(cabin)
        assertEquals(HomeUiState.NoServer, shown(viewModel))
    }
}
