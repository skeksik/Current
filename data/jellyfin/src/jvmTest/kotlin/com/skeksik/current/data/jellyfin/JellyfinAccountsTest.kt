package com.skeksik.current.data.jellyfin

import com.skeksik.current.core.database.DeviceIdentity
import com.skeksik.current.core.database.InMemoryKeyValueStore
import com.skeksik.current.core.database.InMemorySecretStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.AppInfo
import com.skeksik.current.core.model.Permission
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.FirstAddressSources
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerClients
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import com.skeksik.current.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class JellyfinAccountsTest {
    private val serverId = "0123456789abcdef0123456789abcdef"
    private val home = SavedServer(
        ServerRef(ServiceType.jellyfin, "home"),
        serverId,
        "Home",
        listOf(ServerAddress("http://192.168.1.10:8096", AddressScope.Local)),
    )
    private val device = DeviceIdentity("device1", "Desk PC")
    private val secrets = InMemorySecretStore()
    private val settings = InMemoryKeyValueStore()
    private val capabilities = MutableCapabilitiesState().apply { put(ServerStatus(home.ref)) }
    private val requests = mutableListOf<HttpRequestData>()

    /** Answers by path; anything else is a 404. */
    private val routes = mutableMapOf<String, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()

    private class Servers(initial: List<SavedServer>) : ServerRepository {
        override val servers: StateFlow<List<SavedServer>> = MutableStateFlow(initial)
        override fun save(server: SavedServer) = Unit
        override fun remove(ref: ServerRef) = Unit
    }

    private fun CoroutineScope.accounts(quickConnectFor: Duration = 2_000.milliseconds) = StoredJellyfinAccounts(
        servers = Servers(listOf(home)),
        addresses = FirstAddressSources(),
        clients = ServerClients(createHttpClient(MockEngine { request ->
            requests += request
            routes[request.url.encodedPath]?.invoke(this, request) ?: respond("", HttpStatusCode.NotFound)
        })),
        device = device,
        secrets = secrets,
        settings = settings,
        capabilities = capabilities,
        scope = this,
        pollEvery = 10.milliseconds,
        quickConnectFor = quickConnectFor,
    )

    private fun json(body: String): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
        { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }

    private fun status(code: HttpStatusCode): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond("", code) }

    private fun authResult(token: String = "token1", reportedId: String = serverId, admin: Boolean = true) = json(
        """{"User":{"Id":"user1","Name":"Alex","ServerId":"$reportedId","Policy":{"IsAdministrator":$admin,"AuthenticationProviderId":"x",
            |"PasswordResetProviderId":"y"}},"SessionInfo":{},"AccessToken":"$token","ServerId":"$reportedId"}""".trimMargin(),
    )

    private fun requestTo(path: String) = requests.last { it.url.encodedPath == path }

    private val status get() = capabilities.capabilities.value.status(home.ref)!!

    @Test
    fun theHeaderNamesClientDeviceAndVersionAndTheTokenOnlyWhenThereIsOne() {
        val base = "MediaBrowser Client=\"Current\", Device=\"Desk%20PC\", DeviceId=\"device1\", Version=\"${AppInfo.VERSION}\""
        assertEquals(base, JellyfinAuthorization.headerValue(device, null))
        assertEquals("$base, Token=\"a%2Fb\"", JellyfinAuthorization.headerValue(device, "a/b"))
    }

    @Test
    fun aPasswordSignInKeepsTheTokenAndUpdatesTheCapabilities() = runBlocking<Unit> {
        routes["/Users/AuthenticateByName"] = authResult()
        val user = accounts().signIn(home.ref, "alex", "secret pw")

        assertEquals("Alex", user.name)
        assertEquals(setOf(Permission.admin), user.permissions)
        val request = requestTo("/Users/AuthenticateByName")
        assertEquals("""{"Username":"alex","Pw":"secret pw"}""", (request.body as TextContent).text)
        assertFalse("Token=" in request.headers[HttpHeaders.Authorization]!!)
        assertEquals("token1", secrets.get("jellyfin/home/token"))
        assertTrue(status.signedIn)
        assertEquals("Alex", status.user?.name)
    }

    @Test
    fun wrongCredentialsKeepNothing() = runBlocking<Unit> {
        routes["/Users/AuthenticateByName"] = status(HttpStatusCode.Unauthorized)
        val failure = assertFailsWith<ServiceException> { accounts().signIn(home.ref, "alex", "wrong") }
        assertEquals(ServiceErrorKind.Unauthorized, failure.kind)
        assertNull(secrets.get("jellyfin/home/token"))
        assertFalse(status.signedIn)
    }

    @Test
    fun anAnswerFromAnotherServerIsDiscardedAndItsSessionEnded() = runBlocking<Unit> {
        routes["/Users/AuthenticateByName"] = authResult(token = "foreign", reportedId = "fedcba9876543210fedcba9876543210")
        routes["/Sessions/Logout"] = status(HttpStatusCode.NoContent)
        val failure = assertFailsWith<SignInException> { accounts().signIn(home.ref, "alex", "pw") }
        assertEquals(SignInFailure.DifferentServer, failure.failure)
        assertTrue("Token=\"foreign\"" in requestTo("/Sessions/Logout").headers[HttpHeaders.Authorization]!!)
        assertNull(secrets.get("jellyfin/home/token"))
        assertFalse(status.signedIn)
    }

    @Test
    fun quickConnectWaitsForApprovalThenSignsIn() = runBlocking<Unit> {
        var polls = 0
        routes["/QuickConnect/Enabled"] = json("true")
        routes["/QuickConnect/Initiate"] = json("""{"Authenticated":false,"Secret":"s3cret","Code":"123456"}""")
        routes["/QuickConnect/Connect"] = { request ->
            assertEquals("s3cret", request.url.parameters["secret"])
            json("""{"Authenticated":${++polls >= 3},"Secret":"s3cret","Code":"123456"}""")(request)
        }
        routes["/Users/AuthenticateWithQuickConnect"] = authResult(token = "qc-token")

        val accounts = accounts()
        val request = accounts.startQuickConnect(home.ref)
        assertEquals("123456", request.code)
        assertEquals("Alex", accounts.finishQuickConnect(home.ref, request).name)
        assertEquals(3, polls)
        assertEquals("""{"Secret":"s3cret"}""", (requestTo("/Users/AuthenticateWithQuickConnect").body as TextContent).text)
        assertEquals("qc-token", secrets.get("jellyfin/home/token"))
    }

    @Test
    fun quickConnectThatIsOffOrNeverApprovedFails() = runBlocking<Unit> {
        routes["/QuickConnect/Enabled"] = json("false")
        assertEquals(
            SignInFailure.QuickConnectDisabled,
            assertFailsWith<SignInException> { accounts().startQuickConnect(home.ref) }.failure,
        )

        routes["/QuickConnect/Enabled"] = json("true")
        routes["/QuickConnect/Initiate"] = json("""{"Secret":"s3cret","Code":"123456"}""")
        routes["/QuickConnect/Connect"] = json("""{"Authenticated":false}""")
        val accounts = accounts(quickConnectFor = 100.milliseconds)
        val request = accounts.startQuickConnect(home.ref)
        assertEquals(
            SignInFailure.QuickConnectExpired,
            assertFailsWith<SignInException> { accounts.finishQuickConnect(home.ref, request) }.failure,
        )

        routes["/QuickConnect/Connect"] = status(HttpStatusCode.NotFound)
        assertEquals(
            SignInFailure.QuickConnectExpired,
            assertFailsWith<SignInException> { accounts().finishQuickConnect(home.ref, request) }.failure,
        )
    }

    @Test
    fun signingOutEndsTheSessionAndForgetsTheTokenEvenOffline() = runBlocking<Unit> {
        routes["/Users/AuthenticateByName"] = authResult()
        routes["/Sessions/Logout"] = status(HttpStatusCode.NoContent)
        val accounts = accounts()
        accounts.signIn(home.ref, "alex", "pw")
        accounts.signOut(home.ref)
        assertTrue("Token=\"token1\"" in requestTo("/Sessions/Logout").headers[HttpHeaders.Authorization]!!)
        assertNull(secrets.get("jellyfin/home/token"))
        assertNull(settings.get("jellyfin.home.user"))
        assertFalse(status.signedIn)

        accounts.signIn(home.ref, "alex", "pw")
        routes["/Sessions/Logout"] = { throw java.net.ConnectException("offline") }
        accounts.signOut(home.ref)
        assertNull(secrets.get("jellyfin/home/token"))
        assertFalse(status.signedIn)
    }

    @Test
    fun aSavedSessionIsRestoredAndRefreshed() = runBlocking<Unit> {
        routes["/Users/AuthenticateByName"] = authResult()
        accounts().signIn(home.ref, "alex", "pw")
        capabilities.update(home.ref) { ServerStatus(home.ref) }

        routes["/Users/Me"] = json("""{"Id":"user1","Name":"Alex Renamed","Policy":{"IsAdministrator":false}}""")
        accounts().restore().join()
        withTimeout(5_000) { capabilities.capabilities.first { it.status(home.ref)?.user?.name == "Alex Renamed" } }
        assertTrue(status.signedIn)
        assertEquals(emptySet(), status.user?.permissions)
        assertEquals(true, status.reachable)
    }

    @Test
    fun aRevokedTokenSignsOutAtStart() = runBlocking<Unit> {
        routes["/Users/AuthenticateByName"] = authResult()
        accounts().signIn(home.ref, "alex", "pw")
        routes["/Users/Me"] = status(HttpStatusCode.Unauthorized)
        accounts().restore().join()
        withTimeout(5_000) { capabilities.capabilities.first { it.status(home.ref)?.signedIn == false } }
        withTimeout(5_000) { while (secrets.get("jellyfin/home/token") != null) yield() }
    }

    @Test
    fun anOfflineServerStaysSignedInButUnreachable() = runBlocking<Unit> {
        routes["/Users/AuthenticateByName"] = authResult()
        accounts().signIn(home.ref, "alex", "pw")
        routes["/Users/Me"] = { throw java.net.ConnectException("offline") }
        accounts().restore().join()
        withTimeout(5_000) { capabilities.capabilities.first { it.status(home.ref)?.reachable == false } }
        assertTrue(status.signedIn)
    }

    @Test
    fun aUserWithoutATokenIsForgottenAtStart() = runBlocking<Unit> {
        settings.put("jellyfin.home.user", """{"id":"user1","name":"Alex","admin":false}""")
        accounts().restore().join()
        assertNull(settings.get("jellyfin.home.user"))
        assertFalse(status.signedIn)
    }

    @Test
    fun authorizedClientsSendTheTokenAndA401SignsOut() = runBlocking<Unit> {
        routes["/Users/AuthenticateByName"] = authResult()
        val accounts = accounts()
        accounts.signIn(home.ref, "alex", "pw")
        routes["/Items"] = status(HttpStatusCode.Unauthorized)
        val client = assertNotNull(accounts.authorizedClient(home.ref))
        client.use { assertFailsWith<ServiceException> { it.request { get("Items") } } }
        assertTrue("Token=\"token1\"" in requestTo("/Items").headers[HttpHeaders.Authorization]!!)
        withTimeout(5_000) { capabilities.capabilities.first { it.status(home.ref)?.signedIn == false } }
    }
}
