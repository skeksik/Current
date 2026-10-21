package com.skeksik.current.feature.settings

import com.skeksik.current.core.database.InMemoryKeyValueStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerIdentity
import com.skeksik.current.core.network.ServerProbe
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.ServerUrl
import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import com.skeksik.current.core.network.StoredServerRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerSetupViewModelTest {
    private val home = ServerIdentity("0123456789abcdef0123456789abcdef", "Home", "12.2.0")
    private val other = ServerIdentity("fedcba9876543210fedcba9876543210", "Other", "12.2.0")

    /** Answers per address: an identity, a failure, or never (a hanging server). */
    private class FakeProbe(private val answers: Map<String, Any?>) : ServerProbe {
        override val service = ServiceType.jellyfin
        override val defaultPort = 8096
        val asked = mutableListOf<String>()

        override suspend fun identify(server: ServerRef, address: ServerUrl): ServerIdentity {
            asked += address.toString()
            return when (val answer = answers[address.toString()]) {
                is ServerIdentity -> answer
                is ServiceErrorKind -> throw ServiceException(server, answer)
                is Int -> throw ServiceException(server, ServiceErrorKind.InvalidResponse, status = answer)
                "hang" -> awaitCancellation()
                else -> throw ServiceException(server, ServiceErrorKind.Unreachable)
            }
        }
    }

    private val capabilities = MutableCapabilitiesState()

    /** The real repository over an in-memory store, holding [saved]. */
    private fun servers(vararg saved: SavedServer) =
        StoredServerRepository(InMemoryKeyValueStore(), capabilities).apply { saved.forEach(::save) }

    private fun TestScope.viewModel(probe: FakeProbe, servers: ServerRepository = servers(), addTo: ServerRef? = null) =
        ServerSetupViewModel(probe, servers, capabilities, addTo, scope = backgroundScope)

    /** The state once the check has run as far as it can without the clock moving. */
    private fun TestScope.checked(viewModel: ServerSetupViewModel, input: String): ServerSetupUiState {
        viewModel.onInput(input)
        viewModel.check()
        runCurrent()
        return viewModel.state.value
    }

    @Test
    fun httpsWinsWhenBothAnswer() = runTest {
        val probe = FakeProbe(mapOf("https://media.example.com" to home, "http://media.example.com" to home))
        val found = assertIs<ServerSetupUiState.Found>(checked(viewModel(probe), "media.example.com"))
        assertEquals("https://media.example.com", found.address)
        assertEquals("Home", found.name)
        assertFalse(found.plainHttpOnInternet)
    }

    @Test
    fun httpOnTheDefaultPortIsFoundWhenNothingElseAnswers() = runTest {
        val probe = FakeProbe(mapOf("http://192.168.1.10:8096" to home))
        val found = assertIs<ServerSetupUiState.Found>(checked(viewModel(probe), "192.168.1.10"))
        assertEquals("http://192.168.1.10:8096", found.address)
        assertEquals(AddressScope.Local, found.scope)
        assertFalse(found.plainHttpOnInternet)
    }

    @Test
    fun plainHttpOnTheInternetIsFlagged() = runTest {
        val probe = FakeProbe(mapOf("http://media.example.com" to home))
        val found = assertIs<ServerSetupUiState.Found>(checked(viewModel(probe), "http://media.example.com"))
        assertTrue(found.plainHttpOnInternet)
    }

    @Test
    fun aHangingHttpsCandidateDoesNotBlockOnceItFails() = runTest {
        val probe = FakeProbe(mapOf("https://media.lan" to ServiceErrorKind.Timeout, "http://media.lan" to home))
        assertIs<ServerSetupUiState.Found>(checked(viewModel(probe), "media.lan"))
        Unit
    }

    @Test
    fun theMostTellingFailureIsShown() = runTest {
        val redirect = FakeProbe(mapOf("https://media.example.com" to 302))
        assertEquals(ServerSetupProblem.Redirected, checked(viewModel(redirect), "media.example.com").problem())
        val foreign = FakeProbe(mapOf("http://media.example.com" to ServiceErrorKind.NotFound))
        assertEquals(ServerSetupProblem.NotThisService, checked(viewModel(foreign), "media.example.com").problem())
        val silent = FakeProbe(emptyMap())
        assertEquals(ServerSetupProblem.Unreachable, checked(viewModel(silent), "media.example.com").problem())
    }

    @Test
    fun textThatIsNoAddressIsRejectedWithoutAsking() = runTest {
        val probe = FakeProbe(emptyMap())
        assertEquals(ServerSetupProblem.InvalidAddress, checked(viewModel(probe), "  ").problem())
        assertEquals(emptyList(), probe.asked)
    }

    @Test
    fun confirmSavesTheServerAndMarksItReachable() = runTest {
        val servers = servers()
        val viewModel = viewModel(FakeProbe(mapOf("https://media.example.com" to home)), servers)
        checked(viewModel, "https://media.example.com")
        viewModel.confirm()

        val saved = assertIs<ServerSetupUiState.Saved>(viewModel.state.value).server
        assertEquals(listOf(saved), servers.servers.value)
        assertEquals(home.serverId, saved.serverId)
        assertEquals(listOf(ServerAddress("https://media.example.com", AddressScope.Internet)), saved.addresses)
    }

    @Test
    fun aServerThatIsAlreadySavedIsNotAddedTwice() = runTest {
        val saved = SavedServer(ServerRef(ServiceType.jellyfin, "a"), home.serverId, "Home", listOf(ServerAddress("http://192.168.1.10:8096", AddressScope.Local)))
        val probe = FakeProbe(mapOf("https://media.example.com" to home))
        assertEquals(ServerSetupProblem.AlreadyAdded, checked(viewModel(probe, servers(saved)), "media.example.com").problem())
    }

    @Test
    fun anAddressIsAddedOnlyWhenTheSameServerAnswers() = runTest {
        val saved = SavedServer(ServerRef(ServiceType.jellyfin, "a"), home.serverId, "Home", listOf(ServerAddress("http://192.168.1.10:8096", AddressScope.Local)))
        val servers = servers(saved)

        val stranger = viewModel(FakeProbe(mapOf("https://media.example.com" to other)), servers, addTo = saved.ref)
        assertEquals(ServerSetupProblem.DifferentServer, checked(stranger, "media.example.com").problem())

        val same = viewModel(FakeProbe(mapOf("https://media.example.com" to home)), servers, addTo = saved.ref)
        checked(same, "media.example.com")
        same.confirm()
        assertEquals(
            listOf("http://192.168.1.10:8096", "https://media.example.com"),
            servers.servers.value.single().addresses.map { it.url },
        )
        assertEquals(true, capabilities.capabilities.value.status(saved.ref)?.reachable)
    }

    @Test
    fun anAddressForARemovedServerSaysSo() = runTest {
        val saved = SavedServer(ServerRef(ServiceType.jellyfin, "a"), home.serverId, "Home", listOf(ServerAddress("http://192.168.1.10:8096", AddressScope.Local)))
        val servers = servers(saved)
        val probe = FakeProbe(mapOf("https://media.example.com" to home))

        // Removed on another page before the check.
        val early = viewModel(probe, servers, addTo = saved.ref)
        val late = viewModel(probe, servers, addTo = saved.ref)
        assertIs<ServerSetupUiState.Found>(checked(late, "media.example.com"))
        servers.remove(saved.ref)
        assertEquals(ServerSetupProblem.ServerNotSaved, checked(early, "media.example.com").problem())

        // Removed between the check and the confirmation.
        late.confirm()
        assertEquals(ServerSetupUiState.Editing("media.example.com", ServerSetupProblem.ServerNotSaved), late.state.value)
        assertTrue(servers.servers.value.isEmpty())
    }

    @Test
    fun typingCancelsARunningCheck() = runTest {
        val probe = FakeProbe(mapOf("https://media.example.com" to "hang"))
        val viewModel = viewModel(probe)
        viewModel.onInput("https://media.example.com")
        viewModel.check()
        runCurrent()
        assertTrue("https://media.example.com" in probe.asked)
        viewModel.onInput("other")
        runCurrent()
        assertEquals(ServerSetupUiState.Editing("other"), viewModel.state.value)
    }

    private fun ServerSetupUiState.problem() = (this as? ServerSetupUiState.Editing)?.problem
}
