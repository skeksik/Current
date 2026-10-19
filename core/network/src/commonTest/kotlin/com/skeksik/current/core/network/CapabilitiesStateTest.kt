package com.skeksik.current.core.network

import com.skeksik.current.core.model.Capabilities
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.koin.dsl.koinApplication

class CapabilitiesStateTest {
    private val jellyfin = ServerRef(ServiceType.jellyfin, "server-1")

    @Test
    fun startsWithoutServers() {
        assertTrue(MutableCapabilitiesState().capabilities.value.isEmpty)
    }

    @Test
    fun signInAndOutChangeTheStatus() {
        val state = MutableCapabilitiesState()
        state.put(ServerStatus(jellyfin, reachable = true))
        state.update(jellyfin) { it.copy(signedIn = true, user = User("1", jellyfin, "Viewer")) }
        assertTrue(state.capabilities.value.isAvailable(ServiceType.jellyfin))
        assertEquals(true, state.capabilities.value.status(jellyfin)?.reachable)

        state.update(jellyfin) { it.copy(signedIn = false, user = null) }
        assertEquals(false, state.capabilities.value.isAvailable(ServiceType.jellyfin))
    }

    @Test
    fun aLateUpdateDoesNotBringARemovedServerBack() {
        val state = MutableCapabilitiesState(Capabilities(listOf(ServerStatus(jellyfin))))
        state.remove(jellyfin)
        state.update(jellyfin) { it.copy(reachable = true) }
        assertNull(state.capabilities.value.status(jellyfin))
    }

    @Test
    fun updateKeepsTheServer() {
        val state = MutableCapabilitiesState(Capabilities(listOf(ServerStatus(jellyfin))))
        assertFailsWith<IllegalArgumentException> {
            state.update(jellyfin) { it.copy(server = ServerRef(ServiceType.jellyfin, "server-2")) }
        }
    }

    @Test
    fun koinGivesFeaturesAndWritersTheSameState() {
        val koin = koinApplication { modules(networkModule) }
        try {
            assertSame(koin.koin.get<MutableCapabilitiesState>(), koin.koin.get<CapabilitiesState>())
        } finally {
            koin.close()
        }
    }
}
