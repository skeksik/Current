package com.skeksik.current.feature.settings

import com.skeksik.current.core.database.InMemoryKeyValueStore
import com.skeksik.current.core.database.InMemorySecretStore
import com.skeksik.current.core.database.SecretStorage
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.User
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.StoredServerRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServersViewModelTest {
    private val saved = SavedServer(home, "0123456789abcdef0123456789abcdef", "Home", listOf(ServerAddress("http://192.168.1.10:8096", AddressScope.Local)))
    private val capabilities = MutableCapabilitiesState()
    private val servers = StoredServerRepository(InMemoryKeyValueStore(), capabilities).apply { save(saved) }
    private val accounts = FakeAccounts()

    private fun TestScope.viewModel() = ServersViewModel(servers, capabilities, accounts, InMemorySecretStore(), backgroundScope)

    @Test
    fun rowsShowWhoIsSignedInAndWhereTokensAre() = runTest {
        capabilities.update(home) { it.copy(signedIn = true, reachable = true, user = User("u1", home, "Alex")) }
        val viewModel = viewModel()
        runCurrent()
        val state = viewModel.state.value
        assertEquals(listOf(ServerRow(home, "Home", "http://192.168.1.10:8096", "Alex", true)), state.servers)
        assertEquals(SecretStorage.Memory, state.secretStorage)
    }

    @Test
    fun removingSignsOutFirst() = runTest {
        val viewModel = viewModel()
        viewModel.remove(home)
        runCurrent()
        assertTrue(servers.servers.value.isEmpty())
        assertEquals(listOf(home), accounts.signedOut)
        assertTrue(viewModel.state.value.servers.isEmpty())
    }
}
