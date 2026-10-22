package com.skeksik.current.data.jellyfin

import com.skeksik.current.core.database.DeviceIdentity
import com.skeksik.current.core.database.SharedMemoryStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.FirstAddressSources
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerClients
import com.skeksik.current.core.network.StoredServerRepository
import com.skeksik.current.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Two copies of the app over one `localStorage`, as two browser tabs (W7, ADR 0017): sessions follow each other's writes. */
class JellyfinAccountsTabsTest {
    private val serverId = "0123456789abcdef0123456789abcdef"
    private val home = SavedServer(
        ServerRef(ServiceType.jellyfin, "home"),
        serverId,
        "Home",
        listOf(ServerAddress("http://192.168.1.10:8096", AddressScope.Local)),
    )
    private val settingsStorage = SharedMemoryStore()
    private val secretsStorage = SharedMemoryStore()
    private val requests = mutableListOf<HttpRequestData>()

    /** Each sign-in gets the next token, so the two tabs' sessions differ. */
    private var issued = 0

    private inner class Tab(scope: TestScope) {
        val settings = settingsStorage.settings()
        val secrets = secretsStorage.secrets()
        val capabilities = MutableCapabilitiesState()
        val servers = StoredServerRepository(settings, capabilities, scope.backgroundScope)
        val accounts = StoredJellyfinAccounts(
            servers = servers,
            addresses = FirstAddressSources(),
            clients = ServerClients(createHttpClient(MockEngine(MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                addHandler { request ->
                    requests += request
                    when (request.url.encodedPath) {
                        "/Users/AuthenticateByName" -> {
                            issued += 1
                            respond(authResult("token$issued"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                        "/Users/Me" -> respond(USER, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> respond("", HttpStatusCode.NoContent)
                    }
                }
            }))),
            device = DeviceIdentity("device1", "Desk PC"),
            secrets = secrets,
            settings = settings,
            capabilities = capabilities,
            scope = scope.backgroundScope,
        ).also { it.restore() }

        val status get() = capabilities.capabilities.value.status(home.ref)

        /** The token this tab sends now, or null when it sends none. */
        suspend fun token(): String? = accounts.authorizedClient(home.ref)?.use { client ->
            client.request { get("Users/Me") }
            Regex("Token=\"([^\"]+)\"").find(requests.last().headers[HttpHeaders.Authorization]!!)?.groupValues?.get(1)
        }
    }

    private fun authResult(token: String) =
        """{"User":$USER,"SessionInfo":{},"AccessToken":"$token","ServerId":"$serverId"}"""

    /** Both tabs open, the server saved in the first. */
    private fun TestScope.twoTabs(): Pair<Tab, Tab> {
        val a = Tab(this)
        a.servers.save(home)
        val b = Tab(this)
        runCurrent()
        return a to b
    }

    @Test
    fun aSignInInOneTabSignsTheOtherIn() = runTest {
        val (a, b) = twoTabs()
        a.accounts.signIn(home.ref, "alex", "pw")
        runCurrent()
        assertTrue(b.status!!.signedIn)
        assertEquals("Alex", b.status!!.user?.name)
        assertEquals("token1", b.token())
    }

    @Test
    fun aSignOutInOneTabSignsTheOtherOutWithoutWritingAgain() = runTest {
        val (a, b) = twoTabs()
        a.accounts.signIn(home.ref, "alex", "pw")
        runCurrent()
        b.accounts.signOut(home.ref)
        runCurrent()
        assertFalse(a.status!!.signedIn)
        assertNull(a.accounts.authorizedClient(home.ref), "the revoked token is no longer sent")
        assertEquals(1, requests.count { it.url.encodedPath == "/Sessions/Logout" }, "only the tab that signed out ends the session")
    }

    @Test
    fun aServerRemovedInOneTabIsSignedOutAndGoneInTheOther() = runTest {
        val (a, b) = twoTabs()
        a.accounts.signIn(home.ref, "alex", "pw")
        runCurrent()
        b.accounts.signOut(home.ref)
        b.servers.remove(home.ref)
        runCurrent()
        assertNull(a.status)
        assertEquals(emptyList(), a.servers.servers.value)
        assertNull(a.accounts.authorizedClient(home.ref))
    }

    @Test
    fun aSignInWhileAnotherTabSignsOutEndsTheSameInBoth() = runTest {
        val (a, b) = twoTabs()
        a.accounts.signIn(home.ref, "alex", "pw")
        runCurrent()
        // b signs out the session it knows; before either hears of the other, a signs in again.
        b.accounts.signOut(home.ref)
        a.accounts.signIn(home.ref, "alex", "pw")
        runCurrent()
        assertConverged(a, b)
        assertEquals("token2", secretsStorage.secrets().get("jellyfin/home/token"), "the last write, a's new session, stays")
    }

    @Test
    fun aSignOutAfterAnotherTabSignedInEndsTheSameInBoth() = runTest {
        val (a, b) = twoTabs()
        a.accounts.signIn(home.ref, "alex", "pw")
        runCurrent()
        // a signs in again; before b hears of it, b signs out the session it knew.
        a.accounts.signIn(home.ref, "alex", "pw")
        b.accounts.signOut(home.ref)
        runCurrent()
        assertConverged(a, b)
    }

    @Test
    fun aClearedStorageSignsEveryTabOut() = runTest {
        val (a, b) = twoTabs()
        a.accounts.signIn(home.ref, "alex", "pw")
        runCurrent()
        secretsStorage.clear()
        settingsStorage.clear()
        runCurrent()
        assertNull(a.status)
        assertNull(b.status)
        assertNull(b.accounts.authorizedClient(home.ref))
    }

    /** Both tabs show what the stores hold: the same session, or none. */
    private suspend fun TestScope.assertConverged(a: Tab, b: Tab) {
        runCurrent()
        val stored = secretsStorage.secrets().get("jellyfin/home/token")
        listOf(a, b).forEach { tab ->
            assertEquals(stored != null, tab.status!!.signedIn)
            assertEquals(stored, tab.token())
        }
    }

    private companion object {
        const val USER =
            """{"Id":"user1","Name":"Alex","ServerId":"0123456789abcdef0123456789abcdef","Policy":{"IsAdministrator":false,""" +
                """"AuthenticationProviderId":"x","PasswordResetProviderId":"y"}}"""
    }
}
