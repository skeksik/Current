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
import com.skeksik.current.core.network.ServerAddressSource
import com.skeksik.current.core.network.ServerAddressSources
import com.skeksik.current.core.network.ServerUrl
import com.skeksik.current.core.network.ServerClients
import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import com.skeksik.current.core.network.StoredServerRepository
import com.skeksik.current.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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
    private val capabilities = MutableCapabilitiesState()
    private val servers = StoredServerRepository(InMemoryKeyValueStore(), capabilities).apply { save(home) }
    private val requests = mutableListOf<HttpRequestData>()

    /** Answers by path; anything else is a 404. */
    private val routes = mutableMapOf<String, suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>()

    /** On the test's scheduler, so request timeouts, polling and the Quick Connect limit run in virtual time. */
    private fun TestScope.accounts(
        quickConnectFor: Duration = 10.minutes,
        addresses: ServerAddressSources = FirstAddressSources(),
    ) = StoredJellyfinAccounts(
        servers = servers,
        addresses = addresses,
        clients = ServerClients(createHttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests += request
                routes[request.url.encodedPath]?.invoke(this, request) ?: respond("", HttpStatusCode.NotFound)
            }
        }))),
        device = device,
        secrets = secrets,
        settings = settings,
        capabilities = capabilities,
        scope = backgroundScope,
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
    fun aPasswordSignInKeepsTheTokenAndUpdatesTheCapabilities() = runTest {
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
    fun wrongCredentialsKeepNothing() = runTest {
        routes["/Users/AuthenticateByName"] = status(HttpStatusCode.Unauthorized)
        val failure = assertFailsWith<ServiceException> { accounts().signIn(home.ref, "alex", "wrong") }
        assertEquals(ServiceErrorKind.Unauthorized, failure.kind)
        assertNull(secrets.get("jellyfin/home/token"))
        assertFalse(status.signedIn)
    }

    @Test
    fun anAnswerFromAnotherServerIsDiscardedAndItsSessionEnded() = runTest {
        routes["/Users/AuthenticateByName"] = authResult(token = "foreign", reportedId = "fedcba9876543210fedcba9876543210")
        routes["/Sessions/Logout"] = status(HttpStatusCode.NoContent)
        val failure = assertFailsWith<SignInException> { accounts().signIn(home.ref, "alex", "pw") }
        assertEquals(SignInFailure.DifferentServer, failure.failure)
        assertTrue("Token=\"foreign\"" in requestTo("/Sessions/Logout").headers[HttpHeaders.Authorization]!!)
        assertNull(secrets.get("jellyfin/home/token"))
        assertFalse(status.signedIn)
    }

    @Test
    fun quickConnectWaitsForApprovalThenSignsIn() = runTest {
        var polls = 0
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
        assertEquals(10.seconds.inWholeMilliseconds, currentTime)
        assertEquals("""{"Secret":"s3cret"}""", (requestTo("/Users/AuthenticateWithQuickConnect").body as TextContent).text)
        assertEquals("qc-token", secrets.get("jellyfin/home/token"))
    }

    @Test
    fun startingQuickConnectAsksTheServerOnce() = runTest {
        routes["/QuickConnect/Initiate"] = json("""{"Secret":"s3cret","Code":"123456"}""")
        accounts().startQuickConnect(home.ref)
        assertEquals(listOf("/QuickConnect/Initiate"), requests.map { it.url.encodedPath })
    }

    @Test
    fun quickConnectThatIsOffFailsAtStart() = runTest {
        // Jellyfin refuses Initiate with 401 when Quick Connect is off.
        routes["/QuickConnect/Initiate"] = status(HttpStatusCode.Unauthorized)
        assertEquals(
            SignInFailure.QuickConnectDisabled,
            assertFailsWith<SignInException> { accounts().startQuickConnect(home.ref) }.failure,
        )
    }

    @Test
    fun aCodeNeverApprovedOrForgottenExpires() = runTest {
        routes["/QuickConnect/Initiate"] = json("""{"Secret":"s3cret","Code":"123456"}""")
        routes["/QuickConnect/Connect"] = json("""{"Authenticated":false}""")
        val accounts = accounts()
        val request = accounts.startQuickConnect(home.ref)
        assertEquals(
            SignInFailure.QuickConnectExpired,
            assertFailsWith<SignInException> { accounts.finishQuickConnect(home.ref, request) }.failure,
        )
        assertEquals(10.minutes.inWholeMilliseconds, currentTime)

        routes["/QuickConnect/Connect"] = status(HttpStatusCode.NotFound)
        assertEquals(
            SignInFailure.QuickConnectExpired,
            assertFailsWith<SignInException> { accounts.finishQuickConnect(home.ref, request) }.failure,
        )
    }

    @Test
    fun quickConnectKeepsWaitingThroughNetworkAndServerFailures() = runTest {
        val answers = mutableListOf<suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData>(
            { throw kotlinx.io.IOException("offline") },
            status(HttpStatusCode.ServiceUnavailable),
            status(HttpStatusCode.BadGateway),
            json("""{"Authenticated":true}"""),
        )
        routes["/QuickConnect/Initiate"] = json("""{"Secret":"s3cret","Code":"123456"}""")
        routes["/QuickConnect/Connect"] = { request -> answers.removeAt(0)(request) }
        routes["/Users/AuthenticateWithQuickConnect"] = authResult(token = "qc-token")

        val accounts = accounts()
        assertEquals("Alex", accounts.finishQuickConnect(home.ref, accounts.startQuickConnect(home.ref)).name)
        assertTrue(answers.isEmpty())
        assertEquals("qc-token", secrets.get("jellyfin/home/token"))
    }

    @Test
    fun aServerThatNeverRecoversEndsQuickConnectAtTheTimeLimit() = runTest {
        routes["/QuickConnect/Initiate"] = json("""{"Secret":"s3cret","Code":"123456"}""")
        routes["/QuickConnect/Connect"] = status(HttpStatusCode.ServiceUnavailable)
        val accounts = accounts()
        val request = accounts.startQuickConnect(home.ref)
        assertEquals(
            SignInFailure.QuickConnectExpired,
            assertFailsWith<SignInException> { accounts.finishQuickConnect(home.ref, request) }.failure,
        )
        assertEquals(10.minutes.inWholeMilliseconds, currentTime)
    }

    @Test
    fun signingOutEndsTheSessionAndForgetsTheTokenEvenOffline() = runTest {
        routes["/Users/AuthenticateByName"] = authResult()
        routes["/Sessions/Logout"] = status(HttpStatusCode.NoContent)
        val accounts = accounts()
        accounts.signIn(home.ref, "alex", "pw")
        accounts.signOut(home.ref)
        runCurrent()
        assertTrue("Token=\"token1\"" in requestTo("/Sessions/Logout").headers[HttpHeaders.Authorization]!!)
        assertNull(secrets.get("jellyfin/home/token"))
        assertNull(settings.get("jellyfin.home.user"))
        assertFalse(status.signedIn)

        accounts.signIn(home.ref, "alex", "pw")
        routes["/Sessions/Logout"] = { throw kotlinx.io.IOException("offline") }
        accounts.signOut(home.ref)
        runCurrent()
        assertNull(secrets.get("jellyfin/home/token"))
        assertFalse(status.signedIn)
    }

    @Test
    fun signingOutActsHereAtOnceAndEndsTheServerSessionInTheBackground() = runTest {
        routes["/Users/AuthenticateByName"] = authResult()
        val answered = CompletableDeferred<Unit>()
        routes["/Sessions/Logout"] = { answered.await(); respond("", HttpStatusCode.NoContent) }
        val accounts = accounts()
        accounts.signIn(home.ref, "alex", "pw")

        accounts.signOut(home.ref)
        // Nothing has run in the background yet: the session is already gone here.
        assertFalse(status.signedIn)
        assertNull(status.user)
        assertNull(settings.get("jellyfin.home.user"))
        assertNull(accounts.authorizedClient(home.ref))

        runCurrent()
        assertNull(secrets.get("jellyfin/home/token"))
        assertNull(accounts.authorizedClient(home.ref))
        assertTrue("Token=\"token1\"" in requestTo("/Sessions/Logout").headers[HttpHeaders.Authorization]!!)
        answered.complete(Unit)
    }

    /** Shows [shown] as in use, as a roaming source does while it checks again, and proves [proved] once it is set. */
    private class CheckingAgain(val shown: ServerUrl) : ServerAddressSource {
        val proved = MutableStateFlow<ServerUrl?>(null)
        override val current: StateFlow<ServerUrl?> = MutableStateFlow(shown)

        override suspend fun awaitCurrent(): ServerUrl? = proved.filterNotNull().first()

        override fun provedNow(): ServerUrl? = proved.value

        override fun reportUnreachable(address: ServerUrl) = Unit
    }

    private fun signedInWith(source: ServerAddressSource) = ServerAddressSources { if (it.ref == home.ref) source else error("no") }

    @Test
    fun signingOutWhileTheAddressIsCheckedAgainWaitsForAProvedOne() = runTest {
        routes["/Users/AuthenticateByName"] = authResult()
        routes["/Sessions/Logout"] = status(HttpStatusCode.NoContent)
        val source = CheckingAgain(ServerUrl.parse("http://192.168.1.10:8096")!!)
        source.proved.value = source.shown
        val accounts = accounts(addresses = signedInWith(source))
        accounts.signIn(home.ref, "alex", "pw")
        source.proved.value = null
        requests.clear()

        accounts.signOut(home.ref)
        runCurrent()
        assertFalse(status.signedIn)
        assertNull(secrets.get("jellyfin/home/token"))
        // The address shown is not proved since the network changed: the token does not go there.
        assertTrue(requests.isEmpty())

        advanceTimeBy(2.seconds)
        source.proved.value = ServerUrl.parse("https://media.example.com")!!
        runCurrent()
        val logout = requestTo("/Sessions/Logout")
        assertEquals("media.example.com", logout.url.host)
        assertTrue("Token=\"token1\"" in logout.headers[HttpHeaders.Authorization]!!)
    }

    @Test
    fun withoutAProvedAddressSoonTheServerLogoutIsSkipped() = runTest {
        routes["/Users/AuthenticateByName"] = authResult()
        routes["/Sessions/Logout"] = status(HttpStatusCode.NoContent)
        val source = CheckingAgain(ServerUrl.parse("http://192.168.1.10:8096")!!)
        source.proved.value = source.shown
        val accounts = accounts(addresses = signedInWith(source))
        accounts.signIn(home.ref, "alex", "pw")
        source.proved.value = null
        requests.clear()

        accounts.signOut(home.ref)
        advanceTimeBy(5.seconds + 1.milliseconds)
        assertNull(secrets.get("jellyfin/home/token"))
        assertFalse(status.signedIn)
        // Proved too late: the wait is over and nothing is sent.
        source.proved.value = source.shown
        runCurrent()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun aRemovedServersSessionIsStillEndedWithItsOldToken() = runTest {
        routes["/Users/AuthenticateByName"] = authResult()
        routes["/Sessions/Logout"] = status(HttpStatusCode.NoContent)
        val accounts = accounts()
        accounts.signIn(home.ref, "alex", "pw")

        accounts.signOut(home.ref)
        servers.remove(home.ref)
        runCurrent()
        assertTrue("Token=\"token1\"" in requestTo("/Sessions/Logout").headers[HttpHeaders.Authorization]!!)
        assertNull(secrets.get("jellyfin/home/token"))
    }

    @Test
    fun aSavedSessionIsRestoredAndRefreshed() = runTest {
        routes["/Users/AuthenticateByName"] = authResult()
        accounts().signIn(home.ref, "alex", "pw")
        capabilities.update(home.ref) { ServerStatus(home.ref) }

        routes["/Users/Me"] = json("""{"Id":"user1","Name":"Alex Renamed","Policy":{"IsAdministrator":false}}""")
        accounts().restore()
        runCurrent()
        assertEquals("Alex Renamed", status.user?.name)
        assertTrue(status.signedIn)
        assertEquals(emptySet(), status.user?.permissions)
        assertEquals(true, status.reachable)
    }

    @Test
    fun aRevokedTokenSignsOutAtStart() = runTest {
        routes["/Users/AuthenticateByName"] = authResult()
        accounts().signIn(home.ref, "alex", "pw")
        routes["/Users/Me"] = status(HttpStatusCode.Unauthorized)
        accounts().restore()
        runCurrent()
        assertFalse(status.signedIn)
        assertNull(secrets.get("jellyfin/home/token"))
        assertNull(settings.get("jellyfin.home.user"))
    }

    @Test
    fun anOfflineServerStaysSignedInButUnreachable() = runTest {
        routes["/Users/AuthenticateByName"] = authResult()
        accounts().signIn(home.ref, "alex", "pw")
        routes["/Users/Me"] = { throw kotlinx.io.IOException("offline") }
        accounts().restore()
        runCurrent()
        assertEquals(false, status.reachable)
        assertTrue(status.signedIn)
    }

    @Test
    fun aUserWithoutATokenIsForgottenAtStart() = runTest {
        settings.put("jellyfin.home.user", """{"id":"user1","name":"Alex","admin":false}""")
        accounts().restore()
        runCurrent()
        assertNull(settings.get("jellyfin.home.user"))
        assertFalse(status.signedIn)
    }

    @Test
    fun authorizedClientsSendTheTokenAndA401SignsOut() = runTest {
        routes["/Users/AuthenticateByName"] = authResult()
        val accounts = accounts()
        accounts.signIn(home.ref, "alex", "pw")
        routes["/Items"] = status(HttpStatusCode.Unauthorized)
        val client = assertNotNull(accounts.authorizedClient(home.ref))
        client.use { assertFailsWith<ServiceException> { it.request { get("Items") } } }
        assertTrue("Token=\"token1\"" in requestTo("/Items").headers[HttpHeaders.Authorization]!!)
        runCurrent()
        assertFalse(status.signedIn)
        assertNull(secrets.get("jellyfin/home/token"))
    }

    @Test
    fun a401ForAnOldTokenKeepsTheNewSession() = runTest {
        routes["/Users/AuthenticateByName"] = authResult(token = "token1")
        val accounts = accounts()
        accounts.signIn(home.ref, "alex", "pw")
        val old = assertNotNull(accounts.authorizedClient(home.ref))

        // Signing in again with the same device id revokes token1 on the server.
        routes["/Users/AuthenticateByName"] = authResult(token = "token2")
        accounts.signIn(home.ref, "alex", "pw")
        routes["/Items"] = status(HttpStatusCode.Unauthorized)
        old.use { assertFailsWith<ServiceException> { it.request { get("Items") } } }
        runCurrent()

        assertTrue(status.signedIn)
        assertEquals("token2", secrets.get("jellyfin/home/token"))
        assertNotNull(settings.get("jellyfin.home.user"))
        routes["/Items"] = json("[]")
        assertNotNull(accounts.authorizedClient(home.ref)).use { it.request { get("Items") } }
        assertTrue("Token=\"token2\"" in requestTo("/Items").headers[HttpHeaders.Authorization]!!)
    }

    @Test
    fun a401DuringStartCheckKeepsASessionSignedInMeanwhile() = runTest {
        routes["/Users/AuthenticateByName"] = authResult(token = "token1")
        accounts().signIn(home.ref, "alex", "pw")
        settings.remove("jellyfin.home.user")
        capabilities.update(home.ref) { ServerStatus(home.ref) }

        // At the next start the check of token1 is slow; the user signs in again meanwhile, which revokes token1.
        val revoked = CompletableDeferred<Unit>()
        routes["/Users/Me"] = { revoked.await(); respond("", HttpStatusCode.Unauthorized) }
        val accounts = accounts()
        accounts.restore()
        runCurrent()
        routes["/Users/AuthenticateByName"] = authResult(token = "token2")
        accounts.signIn(home.ref, "alex", "pw")
        revoked.complete(Unit)
        runCurrent()

        assertTrue(status.signedIn)
        assertEquals("token2", secrets.get("jellyfin/home/token"))
    }
}
