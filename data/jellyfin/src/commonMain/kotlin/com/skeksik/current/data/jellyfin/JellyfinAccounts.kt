package com.skeksik.current.data.jellyfin

import com.skeksik.current.core.database.DeviceIdentity
import com.skeksik.current.core.database.KeyValueStore
import com.skeksik.current.core.database.SecretStore
import com.skeksik.current.core.model.Permission
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.model.User
import com.skeksik.current.core.network.FixedServerAddress
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerAddressSource
import com.skeksik.current.core.network.ServerAddressSources
import com.skeksik.current.core.network.ServerClient
import com.skeksik.current.core.network.ServerClients
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A Quick Connect request: show [code] to the user, who approves it in another signed-in Jellyfin app. */
class QuickConnectRequest(val code: String, internal val secret: String)

/** Why sign-in failed beyond what [ServiceException] says. */
enum class SignInFailure {
    /** The server is not (or no longer) saved. */
    ServerNotSaved,

    /** The answer came from another server than the one saved; its token is discarded. */
    DifferentServer,

    /** Quick Connect is turned off on the server. */
    QuickConnectDisabled,

    /** The code was not approved in time, or the server forgot it. */
    QuickConnectExpired,
}

class SignInException(val failure: SignInFailure) : Exception("Sign-in failed: $failure")

/**
 * Jellyfin sessions (ADR 0018): sign in with a password or Quick Connect, keep the token in the [SecretStore], restore sessions at
 * start, and sign out. The capabilities state follows every change. Calls throw [ServiceException] for request failures and
 * [SignInException] for the rest.
 */
interface JellyfinAccounts {
    suspend fun signIn(server: ServerRef, username: String, password: String): User

    suspend fun quickConnectEnabled(server: ServerRef): Boolean

    suspend fun startQuickConnect(server: ServerRef): QuickConnectRequest

    /** Waits until the code is approved, then signs in. Throws [SignInFailure.QuickConnectExpired] when it is not approved in time. */
    suspend fun finishQuickConnect(server: ServerRef, request: QuickConnectRequest): User

    /**
     * Forgets the session here at once (token, user, capabilities), then ends it on the server in the background, best effort, so the
     * server may be removed right after.
     */
    suspend fun signOut(server: ServerRef)

    /** A client that sends [server]'s token, or null when signed out. A 401 signs the server out if the token is still current. Callers close it. */
    suspend fun authorizedClient(server: ServerRef): ServerClient?
}

internal class StoredJellyfinAccounts(
    private val servers: ServerRepository,
    private val addresses: ServerAddressSources,
    private val clients: ServerClients,
    private val device: DeviceIdentity,
    private val secrets: SecretStore,
    private val settings: KeyValueStore,
    private val capabilities: MutableCapabilitiesState,
    /** Lives as long as the app: restores sessions and handles 401s. */
    private val scope: CoroutineScope,
    private val pollEvery: Duration = 5.seconds,
    /** Quick Connect codes are valid for 10 minutes. */
    private val quickConnectFor: Duration = 10.minutes,
) : JellyfinAccounts {
    /** Tokens in memory, so requests do not ask the keyring each time. Updated atomically: restore runs beside the UI. */
    private val tokens = MutableStateFlow<Map<ServerRef, String>>(emptyMap())

    /** Keeps a token being stored and a stale one being removed from the [SecretStore] apart, so a removal never hits a newer token. */
    private val secretsLock = Mutex()

    /** Signed out here, with the stored token not removed yet: [authorizedClient] must not read it back. */
    private val signingOut = MutableStateFlow<Set<ServerRef>>(emptySet())

    /**
     * Puts saved sessions into the capabilities state, then checks each with its server in the background. From then on, follows the
     * sessions other copies of the app sharing the stores (browser tabs) start and end.
     */
    fun restore(): Job =
        scope.launch {
            follow()
            servers.servers.value.filter { it.ref.service == ServiceType.jellyfin }.forEach { server ->
                val token = secrets.get(tokenKey(server.ref))
                val user = settings.get(userKey(server.ref))?.let(::decodeUser)
                if (token == null) {
                    if (user != null) settings.remove(userKey(server.ref))
                    return@forEach
                }
                tokens.update { it + (server.ref to token) }
                if (user != null) markSignedIn(server.ref, user.toUser(server.ref))
                launch { verify(server, token) }
            }
        }

    /**
     * Another copy wrote a token or a user: this copy takes on what the stores hold now, so every copy converges on the last write. It
     * only reads: the copy that wrote has already stored or removed the session, and ended it on the server.
     */
    private fun follow() {
        // The servers a change touches; every one for a cleared store (a null key).
        fun touched(key: String?, owner: (String) -> ServerRef?): List<ServerRef> =
            if (key == null) (servers.servers.value.map { it.ref } + tokens.value.keys).distinct() else listOfNotNull(owner(key))
        val changed = merge(
            secrets.changedElsewhere.map { key -> touched(key) { tokenOwner(it) } },
            settings.changedElsewhere.map { key -> touched(key) { userOwner(it) } },
        )
        scope.launch { changed.collect { refs -> refs.forEach { adopt(it) } } }
    }

    /**
     * Takes on [server]'s session as the stores hold it now. While this copy signs [server] out, its own sign-out finishes first and
     * adopts after.
     */
    private suspend fun adopt(server: ServerRef) {
        if (server.service != ServiceType.jellyfin || server in signingOut.value) return
        secretsLock.withLock {
            val stored = secrets.get(tokenKey(server))
            if (stored == null) {
                val signedIn = capabilities.capabilities.value.status(server)?.signedIn == true
                if (tokens.value[server] == null && !signedIn) return@withLock
                tokens.update { it - server }
                capabilities.update(server) { it.copy(signedIn = false, user = null) }
                return@withLock
            }
            tokens.update { it + (server to stored) }
            settings.get(userKey(server))?.let(::decodeUser)?.let { markSignedIn(server, it.toUser(server)) }
        }
    }

    private suspend fun verify(server: SavedServer, token: String) {
        try {
            val user = clientFor(server, token).use { client -> client.request { get("Users/Me").body<UserDto>() } }
            remember(server.ref, user)
            capabilities.update(server.ref) { it.copy(reachable = true) }
        } catch (e: ServiceException) {
            when (e.kind) {
                ServiceErrorKind.Unauthorized -> Unit // onUnauthorized has signed it out.
                ServiceErrorKind.Unreachable, ServiceErrorKind.Timeout -> capabilities.update(server.ref) { it.copy(reachable = false) }
                else -> Unit
            }
        }
    }

    override suspend fun signIn(server: ServerRef, username: String, password: String): User {
        val saved = saved(server)
        val result = clientFor(saved, token = null).use { client ->
            client.request {
                post("Users/AuthenticateByName") {
                    contentType(ContentType.Application.Json)
                    setBody(AuthenticateByNameDto(username, password))
                }.body<AuthenticationResultDto>()
            }
        }
        return complete(saved, result)
    }

    override suspend fun quickConnectEnabled(server: ServerRef): Boolean =
        clientFor(saved(server), token = null).use { client -> client.request { get("QuickConnect/Enabled").body<Boolean>() } }

    override suspend fun startQuickConnect(server: ServerRef): QuickConnectRequest {
        val saved = saved(server)
        val started = try {
            clientFor(saved, token = null).use { client -> client.request { post("QuickConnect/Initiate").body<QuickConnectResultDto>() } }
        } catch (e: ServiceException) {
            // Jellyfin answers 401 when Quick Connect is off, so no separate check is needed.
            if (e.kind == ServiceErrorKind.Unauthorized) throw SignInException(SignInFailure.QuickConnectDisabled) else throw e
        }
        if (started.secret.isBlank() || started.code.isBlank()) throw ServiceException(server, ServiceErrorKind.InvalidResponse)
        return QuickConnectRequest(started.code, started.secret)
    }

    override suspend fun finishQuickConnect(server: ServerRef, request: QuickConnectRequest): User {
        val saved = saved(server)
        return clientFor(saved, token = null).use { client ->
            val approved = withTimeoutOrNull(quickConnectFor) {
                while (!poll(client, request)) delay(pollEvery)
                true
            }
            if (approved == null) throw SignInException(SignInFailure.QuickConnectExpired)
            val result = client.request {
                post("Users/AuthenticateWithQuickConnect") {
                    contentType(ContentType.Application.Json)
                    setBody(QuickConnectSecretDto(request.secret))
                }.body<AuthenticationResultDto>()
            }
            complete(saved, result)
        }
    }

    /**
     * True once approved. The server forgets unknown or expired secrets (404). No answer or a server error counts as not yet: the user
     * may be walking to another device while the network changes, and the time limit ends the wait.
     */
    private suspend fun poll(client: ServerClient, request: QuickConnectRequest): Boolean = try {
        client.request { get("QuickConnect/Connect") { parameter("secret", request.secret) }.body<QuickConnectResultDto>() }.authenticated
    } catch (e: ServiceException) {
        when (e.kind) {
            ServiceErrorKind.NotFound -> throw SignInException(SignInFailure.QuickConnectExpired)
            ServiceErrorKind.Unreachable, ServiceErrorKind.Timeout, ServiceErrorKind.ServerError -> false
            else -> throw e
        }
    }

    override suspend fun signOut(server: ServerRef) {
        // Everything the caller needs happens before the first suspension, so a cancelled caller cannot leave it half done.
        val saved = servers.server(server)
        // The address in use now is proved; after the server is removed its roaming source has none.
        val address = saved?.let(addresses::forServer)?.let { source -> source.current.value?.let(::FixedServerAddress) ?: source }
        val known = tokens.value[server]
        signingOut.update { it + server }
        tokens.update { it - server }
        settings.remove(userKey(server))
        capabilities.update(server) { it.copy(signedIn = false, user = null) }
        scope.launch {
            // Without a token in memory, restore has not read it yet: the stored one is this session's.
            val token = try {
                forgetStored(server, known)
            } finally {
                signingOut.update { it - server }
            }
            // Another copy may have signed in meanwhile: its session stays stored, and this copy takes it on.
            adopt(server)
            if (token != null && saved != null && address != null) endSession(saved, token, address)
        }
    }

    override suspend fun authorizedClient(server: ServerRef): ServerClient? {
        val saved = servers.server(server) ?: return null
        val token = tokens.value[server]
            ?: secrets.get(tokenKey(server))?.takeIf { server !in signingOut.value }?.also { found -> tokens.update { it + (server to found) } }
            ?: return null
        return clientFor(saved, token)
    }

    /** Checks the answer came from the saved server, then keeps the session. */
    private suspend fun complete(saved: SavedServer, result: AuthenticationResultDto): User {
        val token = result.accessToken?.takeIf { it.isNotBlank() }
        val user = result.user
        if (token == null || user == null) throw ServiceException(saved.ref, ServiceErrorKind.InvalidResponse)
        val reported = result.serverId ?: user.serverId
        if (reported != null && normalisedId(reported) != normalisedId(saved.serverId)) {
            endSession(saved, token, addresses.forServer(saved))
            throw SignInException(SignInFailure.DifferentServer)
        }
        secretsLock.withLock {
            secrets.put(tokenKey(saved.ref), token)
            tokens.update { it + (saved.ref to token) }
        }
        return remember(saved.ref, user).also { capabilities.update(saved.ref) { it.copy(reachable = true) } }
    }

    private suspend fun endSession(server: SavedServer, token: String, address: ServerAddressSource) {
        try {
            clientFor(server, token, address, signOutOn401 = false).use { client ->
                client.request { post("Sessions/Logout") { timeout { requestTimeoutMillis = 5_000 } } }
            }
        } catch (e: ServiceException) {
            // Best effort: the token is forgotten here either way.
        }
    }

    private fun remember(server: ServerRef, dto: UserDto): User {
        val stored = StoredUser(dto.id, dto.name ?: "", dto.policy?.isAdministrator == true)
        settings.put(userKey(server), json.encodeToString(StoredUser.serializer(), stored))
        return stored.toUser(server).also { markSignedIn(server, it) }
    }

    private fun markSignedIn(server: ServerRef, user: User) =
        capabilities.update(server) { it.copy(signedIn = true, user = user) }

    /**
     * Signs [server] out after a 401 for [token], only while [token] is still its session: a request sent before a new sign-in gets a
     * 401 for the old token (Jellyfin revokes it), and must not end the new session.
     */
    private suspend fun forget(server: ServerRef, token: String) {
        secretsLock.withLock {
            if (tokens.value[server] != token) return
            removeToken(server, token)
            settings.remove(userKey(server))
            capabilities.update(server) { it.copy(signedIn = false, user = null) }
        }
    }

    /** Removes the stored token of a signed-out session: [known] if it was in memory, else whatever is stored. Returns it, if any. */
    private suspend fun forgetStored(server: ServerRef, known: String?): String? = secretsLock.withLock {
        val current = tokens.value[server]
        // A new session was kept since.
        if (current != null && current != known) return@withLock null
        val token = known ?: secrets.get(tokenKey(server)) ?: return@withLock null
        removeToken(server, token)
        token
    }

    /** Call with [secretsLock] held. */
    private suspend fun removeToken(server: ServerRef, token: String) {
        if (secrets.get(tokenKey(server)) == token) secrets.remove(tokenKey(server))
        tokens.update { if (it[server] == token) it - server else it }
    }

    private fun clientFor(
        server: SavedServer,
        token: String?,
        address: ServerAddressSource = addresses.forServer(server),
        signOutOn401: Boolean = true,
    ): ServerClient = clients.create(
        server = server.ref,
        address = address,
        auth = JellyfinAuthorization(device) { token },
        onUnauthorized = if (token != null && signOutOn401) ({ scope.launch { forget(server.ref, token) } }) else null,
    )

    private fun saved(server: ServerRef): SavedServer =
        servers.server(server)?.takeIf { it.ref.service == ServiceType.jellyfin } ?: throw SignInException(SignInFailure.ServerNotSaved)

    private fun decodeUser(text: String): StoredUser? = try {
        json.decodeFromString(StoredUser.serializer(), text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** In the [SecretStore]. */
        fun tokenKey(server: ServerRef) = "jellyfin/${server.key}/token"

        /** In settings: not secret, and needed to show who is signed in while the server is offline. */
        fun userKey(server: ServerRef) = "jellyfin.${server.key}.user"

        fun tokenOwner(key: String): ServerRef? = ownerOf(key, "jellyfin/", "/token")

        fun userOwner(key: String): ServerRef? = ownerOf(key, "jellyfin.", ".user")

        private fun ownerOf(key: String, start: String, end: String): ServerRef? {
            if (!key.startsWith(start) || !key.endsWith(end) || key.length <= start.length + end.length) return null
            return ServerRef(ServiceType.jellyfin, key.substring(start.length, key.length - end.length))
        }

        /** Jellyfin writes ids as 32 hex characters, but some fields may carry dashes or capitals. */
        fun normalisedId(id: String) = id.replace("-", "").lowercase()
    }
}

@Serializable
private data class StoredUser(val id: String, val name: String, val admin: Boolean) {
    fun toUser(server: ServerRef) = User(id, server, name, if (admin) setOf(Permission.admin) else emptySet())
}
