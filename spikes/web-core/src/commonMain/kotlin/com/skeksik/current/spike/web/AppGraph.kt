package com.skeksik.current.spike.web

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.random.Random

/** String storage; `localStorage` in the browser. */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

@Serializable
internal data class StoredSession(
    val serverUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
    val viaApiKey: Boolean = false,
)

/** Everything the screens share: storage, the session, the HTTP client and the device profile. One per page load. */
class AppGraph(
    private val storage: KeyValueStore,
    httpEngine: HttpClientEngine,
    /** Probing is asynchronous in the browser (`decodingInfo`); started at page load, awaited before the first PlaybackInfo. */
    val mediaSupport: Deferred<MediaSupport>,
    deviceName: String = "Web browser",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val http = createHttpClient(httpEngine)

    val clientInfo = ClientInfo(
        deviceId = storage.get(KEY_DEVICE_ID) ?: randomId().also { storage.set(KEY_DEVICE_ID, it) },
        device = deviceName,
    )

    private val _session = MutableStateFlow(loadSession())
    val session: StateFlow<Session?> = _session.asStateFlow()

    val lastServerUrl: String get() = storage.get(KEY_SERVER).orEmpty()

    private var profile: DeviceProfileDto? = null

    internal suspend fun deviceProfile(): DeviceProfileDto = profile ?: WebDeviceProfile.build(mediaSupport.await()).also { profile = it }

    internal fun api(serverUrl: String, token: String?) = JellyfinApi(http, serverUrl, clientInfo, token)

    internal fun api(session: Session) = api(session.serverUrl, session.accessToken)

    internal fun signedIn(session: Session) {
        storage.set(KEY_SERVER, session.serverUrl)
        storage.set(KEY_SESSION, JellyfinJson.encodeToString(StoredSession.serializer(), session.toStored()))
        _session.value = session
    }

    /** Forgets the token here and revokes it on the server (except API-key sessions, whose key must survive). */
    fun signOut() {
        val current = _session.value ?: return
        storage.set(KEY_SESSION, null)
        _session.value = null
        if (!current.viaApiKey) launchDetached { api(current).logout() }
    }

    /** Work that must finish after a screen is gone (stop a transcode, log out). Failures are ignored. */
    internal fun launchDetached(block: suspend () -> Unit) {
        scope.launch { attempt { block() } }
    }

    fun signInViewModel() = SignInViewModel(this)
    fun homeViewModel() = HomeViewModel(this)
    fun libraryViewModel(libraryId: String) = LibraryViewModel(this, libraryId)
    fun itemViewModel(itemId: String) = ItemViewModel(this, itemId)
    fun playerViewModel(itemId: String) = PlayerViewModel(this, itemId)

    private fun loadSession(): Session? = storage.get(KEY_SESSION)?.let { json ->
        runCatching { JellyfinJson.decodeFromString(StoredSession.serializer(), json) }.getOrNull()?.toSession()
    }

    private fun Session.toStored() = StoredSession(serverUrl, accessToken, userId, userName, viaApiKey)
    private fun StoredSession.toSession() = Session(serverUrl, accessToken, userId, userName, viaApiKey)

    companion object {
        const val KEY_SESSION = "current.spike.session"
        const val KEY_DEVICE_ID = "current.spike.deviceId"
        const val KEY_SERVER = "current.spike.server"

        private fun randomId() = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
