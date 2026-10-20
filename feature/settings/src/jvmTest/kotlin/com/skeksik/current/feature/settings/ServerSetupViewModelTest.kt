package com.skeksik.current.feature.settings

import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerIdentity
import com.skeksik.current.core.network.ServerProbe
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.ServerUrl
import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    private class FakeServers(initial: List<SavedServer> = emptyList()) : ServerRepository {
        private val state = MutableStateFlow(initial)
        override val servers: StateFlow<List<SavedServer>> = state

        override fun save(server: SavedServer) {
            state.value = state.value.filterNot { it.ref == server.ref } + server
        }

        override fun remove(ref: ServerRef) {
            state.value = state.value.filterNot { it.ref == ref }
        }
    }

    private val capabilities = MutableCapabilitiesState()

    private fun CoroutineScope.viewModel(probe: FakeProbe, servers: ServerRepository = FakeServers(), addTo: ServerRef? = null) =
        ServerSetupViewModel(probe, servers, capabilities, addTo, scope = this)

    private suspend fun ServerSetupViewModel.checked(input: String): ServerSetupUiState {
        onInput(input)
        check()
        return withTimeout(5_000) { state.first { it !is ServerSetupUiState.Checking } }
    }

    @Test
    fun httpsWinsWhenBothAnswer() = runBlocking {
        val probe = FakeProbe(mapOf("https://media.example.com" to home, "http://media.example.com" to home))
        val found = assertIs<ServerSetupUiState.Found>(viewModel(probe).checked("media.example.com"))
        assertEquals("https://media.example.com", found.address)
        assertEquals("Home", found.name)
        assertFalse(found.plainHttpOnInternet)
    }

    @Test
    fun httpOnTheDefaultPortIsFoundWhenNothingElseAnswers() = runBlocking {
        val probe = FakeProbe(mapOf("http://192.168.1.10:8096" to home))
        val found = assertIs<ServerSetupUiState.Found>(viewModel(probe).checked("192.168.1.10"))
        assertEquals("http://192.168.1.10:8096", found.address)
        assertEquals(AddressScope.Local, found.scope)
        assertFalse(found.plainHttpOnInternet)
    }

    @Test
    fun plainHttpOnTheInternetIsFlagged() = runBlocking {
        val probe = FakeProbe(mapOf("http://media.example.com" to home))
        val found = assertIs<ServerSetupUiState.Found>(viewModel(probe).checked("http://media.example.com"))
        assertTrue(found.plainHttpOnInternet)
    }

    @Test
    fun aHangingHttpsCandidateDoesNotBlockOnceItFails() = runBlocking {
        val probe = FakeProbe(mapOf("https://media.lan" to ServiceErrorKind.Timeout, "http://media.lan" to home))
        assertIs<ServerSetupUiState.Found>(viewModel(probe).checked("media.lan"))
        Unit
    }

    @Test
    fun theMostTellingFailureIsShown() = runBlocking {
        val redirect = FakeProbe(mapOf("https://media.example.com" to 302))
        assertEquals(ServerSetupProblem.Redirected, viewModel(redirect).checked("media.example.com").problem())
        val foreign = FakeProbe(mapOf("http://media.example.com" to ServiceErrorKind.NotFound))
        assertEquals(ServerSetupProblem.NotThisService, viewModel(foreign).checked("media.example.com").problem())
        val silent = FakeProbe(emptyMap())
        assertEquals(ServerSetupProblem.Unreachable, viewModel(silent).checked("media.example.com").problem())
    }

    @Test
    fun textThatIsNoAddressIsRejectedWithoutAsking() = runBlocking {
        val probe = FakeProbe(emptyMap())
        assertEquals(ServerSetupProblem.InvalidAddress, viewModel(probe).checked("  ").problem())
        assertEquals(emptyList(), probe.asked)
    }

    @Test
    fun confirmSavesTheServerAndMarksItReachable() = runBlocking {
        val servers = FakeServers()
        val viewModel = viewModel(FakeProbe(mapOf("https://media.example.com" to home)), servers)
        viewModel.checked("https://media.example.com")
        viewModel.confirm()

        val saved = assertIs<ServerSetupUiState.Saved>(viewModel.state.value).server
        assertEquals(listOf(saved), servers.servers.value)
        assertEquals(home.serverId, saved.serverId)
        assertEquals(listOf(ServerAddress("https://media.example.com", AddressScope.Internet)), saved.addresses)
    }

    @Test
    fun aServerThatIsAlreadySavedIsNotAddedTwice() = runBlocking {
        val saved = SavedServer(ServerRef(ServiceType.jellyfin, "a"), home.serverId, "Home", listOf(ServerAddress("http://192.168.1.10:8096", AddressScope.Local)))
        val probe = FakeProbe(mapOf("https://media.example.com" to home))
        assertEquals(ServerSetupProblem.AlreadyAdded, viewModel(probe, FakeServers(listOf(saved))).checked("media.example.com").problem())
    }

    @Test
    fun anAddressIsAddedOnlyWhenTheSameServerAnswers() = runBlocking {
        val saved = SavedServer(ServerRef(ServiceType.jellyfin, "a"), home.serverId, "Home", listOf(ServerAddress("http://192.168.1.10:8096", AddressScope.Local)))
        val servers = FakeServers(listOf(saved))
        capabilities.put(ServerStatus(saved.ref))

        val stranger = viewModel(FakeProbe(mapOf("https://media.example.com" to other)), servers, addTo = saved.ref)
        assertEquals(ServerSetupProblem.DifferentServer, stranger.checked("media.example.com").problem())

        val same = viewModel(FakeProbe(mapOf("https://media.example.com" to home)), servers, addTo = saved.ref)
        same.checked("media.example.com")
        same.confirm()
        assertEquals(
            listOf("http://192.168.1.10:8096", "https://media.example.com"),
            servers.servers.value.single().addresses.map { it.url },
        )
        assertEquals(true, capabilities.capabilities.value.status(saved.ref)?.reachable)
    }

    @Test
    fun typingCancelsARunningCheck() = runBlocking {
        val probe = FakeProbe(mapOf("https://media.example.com" to "hang"))
        val viewModel = viewModel(probe)
        viewModel.onInput("https://media.example.com")
        viewModel.check()
        viewModel.onInput("other")
        assertEquals(ServerSetupUiState.Editing("other"), viewModel.state.value)
    }

    private fun ServerSetupUiState.problem() = (this as? ServerSetupUiState.Editing)?.problem
}
