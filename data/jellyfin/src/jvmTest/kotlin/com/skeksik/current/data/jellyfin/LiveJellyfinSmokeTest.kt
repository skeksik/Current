package com.skeksik.current.data.jellyfin

import com.skeksik.current.core.database.DeviceIdentity
import com.skeksik.current.core.database.InMemoryKeyValueStore
import com.skeksik.current.core.database.InMemorySecretStore
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.FirstAddressSources
import com.skeksik.current.core.network.FixedServerAddress
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerClients
import com.skeksik.current.core.network.ServerIdentity
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.ServerUrl
import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import com.skeksik.current.core.network.candidates
import com.skeksik.current.core.network.createHttpClient
import com.skeksik.current.core.network.scope
import io.ktor.client.call.body
import io.ktor.client.request.get
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Opt-in checks against a real Jellyfin server (roadmap, M1). Never in CI, and nothing about the server is in the repo: everything comes
 * from the environment.
 * - `CURRENT_LIVE_JELLYFIN_URL`: the address as a user would type it (with or without scheme). Without it, every test here is skipped.
 * - `CURRENT_LIVE_QUICK_CONNECT=1`: also sign in with Quick Connect. The code is printed and written to
 *   `data/jellyfin/build/live-smoke/quick-connect-code.txt`; approve it in a signed-in Jellyfin app within 10 minutes.
 * - `CURRENT_LIVE_JELLYFIN_USERNAME` and `CURRENT_LIVE_JELLYFIN_PASSWORD`: also sign in with a password.
 *
 * Read-only apart from the session a sign-in creates, which each test ends again. The device shows up on the server as
 * "Current smoke test". Run with `./gradlew :data:jellyfin:jvmTest --tests "*LiveJellyfinSmokeTest" --rerun`. Output never contains
 * tokens; request log lines name the server by its key, not its host.
 */
class LiveJellyfinSmokeTest {
    private val typed: String? = System.getenv("CURRENT_LIVE_JELLYFIN_URL")?.takeIf { it.isNotBlank() }
    private val ref = ServerRef(ServiceType.jellyfin, "live-smoke")
    private val device = DeviceIdentity("current-live-smoke-test", "Current smoke test")
    private val clients = ServerClients(createHttpClient(), logger = { println("LIVE request: $it") })

    private class Servers(server: SavedServer) : ServerRepository {
        override val servers: StateFlow<List<SavedServer>> = MutableStateFlow(listOf(server))
        override fun save(server: SavedServer) = Unit
        override fun remove(ref: ServerRef) = Unit
    }

    /** The first candidate address that answers, as server setup picks it. */
    private suspend fun find(): Pair<ServerUrl, ServerIdentity> {
        val probe = JellyfinServerProbe(clients)
        val candidates = ServerUrl.candidates(typed!!, probe.defaultPort)
        assertTrue(candidates.isNotEmpty(), "CURRENT_LIVE_JELLYFIN_URL is no address")
        var last: ServiceException? = null
        for (url in candidates) {
            try {
                return url to probe.identify(ref, url)
            } catch (e: ServiceException) {
                println("LIVE candidate ${if (url.isHttps) "https" else "http"} failed: ${e.kind} ${e.status ?: ""}")
                last = e
            }
        }
        throw assertNotNull(last)
    }

    private fun CoroutineScope.accounts(server: SavedServer, capabilities: MutableCapabilitiesState, secrets: InMemorySecretStore, settings: InMemoryKeyValueStore) =
        StoredJellyfinAccounts(
            servers = Servers(server),
            addresses = FirstAddressSources(),
            clients = clients,
            device = device,
            secrets = secrets,
            settings = settings,
            capabilities = capabilities,
            scope = this,
            pollEvery = 3.seconds,
            quickConnectFor = 10.minutes,
        )

    @Test
    fun theServerIdentifiesItselfWithoutCredentials() = runBlocking<Unit> {
        if (typed == null) return@runBlocking
        val (url, identity) = find()
        println("LIVE found ${if (url.isHttps) "https" else "http"} (${url.scope}) server version ${identity.version}")
        assertEquals(32, identity.serverId.length, "server id is 32 hex characters")
        assertTrue(identity.version?.startsWith("12.") == true, "a 12.x server (latest releases only)")
        val enabled = clients.create(ref, FixedServerAddress(url), JellyfinAuthorization(device) { null }).use { client ->
            client.request { get("QuickConnect/Enabled").body<Boolean>() }
        }
        println("LIVE Quick Connect enabled: $enabled")
    }

    @Test
    fun quickConnectSignsInRestoresAndSignsOut() = runBlocking<Unit> {
        if (typed == null || System.getenv("CURRENT_LIVE_QUICK_CONNECT") != "1") return@runBlocking
        signInAndOut { accounts ->
            val request = accounts.startQuickConnect(ref)
            File("build/live-smoke").mkdirs()
            File("build/live-smoke/quick-connect-code.txt").writeText(request.code)
            println("LIVE Quick Connect code: ${request.code} (approve it in a signed-in Jellyfin app)")
            accounts.finishQuickConnect(ref, request).name
        }
    }

    @Test
    fun aPasswordSignsInRestoresAndSignsOut() = runBlocking<Unit> {
        val username = System.getenv("CURRENT_LIVE_JELLYFIN_USERNAME")
        val password = System.getenv("CURRENT_LIVE_JELLYFIN_PASSWORD")
        if (typed == null || username.isNullOrBlank() || password == null) return@runBlocking
        signInAndOut { accounts -> accounts.signIn(ref, username, password).name }
    }

    /** Signs in with [signIn], checks the session, restores it in a second instance as after a restart, signs out, checks it ended. */
    private suspend fun CoroutineScope.signInAndOut(signIn: suspend (StoredJellyfinAccounts) -> String) {
        val (url, identity) = find()
        val server = SavedServer(ref, identity.serverId, identity.name, listOf(ServerAddress(url.toString(), url.scope)))
        val secrets = InMemorySecretStore()
        val settings = InMemoryKeyValueStore()
        val capabilities = MutableCapabilitiesState().apply { put(ServerStatus(ref)) }
        val accounts = accounts(server, capabilities, secrets, settings)

        val name = signIn(accounts)
        println("LIVE signed in as a user named with ${name.length} characters")
        assertTrue(capabilities.capabilities.value.status(ref)!!.signedIn)
        val token = assertNotNull(secrets.get("jellyfin/live-smoke/token"))
        assertNotNull(accounts.authorizedClient(ref)).use { client -> client.request { get("Users/Me") } }

        val afterRestart = MutableCapabilitiesState().apply { put(ServerStatus(ref)) }
        accounts(server, afterRestart, secrets, settings).restore().join()
        withTimeout(30_000) { afterRestart.capabilities.first { it.status(ref)?.reachable == true } }
        assertTrue(afterRestart.capabilities.value.status(ref)!!.signedIn)
        println("LIVE restored session checked with /Users/Me")

        accounts.signOut(ref)
        assertTrue(!capabilities.capabilities.value.status(ref)!!.signedIn)
        val stale = clients.create(ref, FixedServerAddress(url), JellyfinAuthorization(device) { token })
        val rejected = assertFailsWith<ServiceException> { stale.use { client -> client.request { get("Users/Me") } } }
        assertEquals(ServiceErrorKind.Unauthorized, rejected.kind, "the server ended the session")
        println("LIVE signed out; the old token is rejected")
    }
}
