package com.skeksik.current.core.network

import com.skeksik.current.core.database.KeyValueStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** The servers the user added, kept across restarts (ADR 0016). Server setup writes it; everything else reads [servers]. */
interface ServerRepository {
    /** In the order they were added. */
    val servers: StateFlow<List<SavedServer>>

    fun server(ref: ServerRef): SavedServer? = servers.value.firstOrNull { it.ref == ref }

    /** Adds [server], or replaces the saved server with the same ref (to change its addresses or name). */
    fun save(server: SavedServer)

    fun remove(ref: ServerRef)
}

/** A new opaque key for a server being added: random, unique, and saying nothing about where the server runs (ADR 0013). */
fun newServerRef(service: ServiceType): ServerRef = ServerRef(service, Random.nextBytes(16).joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
})

/**
 * Keeps each server under its own key (`server.<key>`) and their order under `servers`, so no value grows with the number of servers.
 * Every saved server is in the capabilities state from the start, signed out until sign-in says otherwise; removing one removes it there.
 * Unreadable entries are skipped. Call it from one thread, as the UI does.
 *
 * Another copy of the app may share the store (a browser tab, ADR 0017): with a [scope], the list reloads when that copy changes it, and
 * the index is always changed from what the store holds, so this copy never writes back a list the other has changed since.
 * Public so other modules' tests can use the real repository on an `InMemoryKeyValueStore`; the app binds it in Koin.
 */
class StoredServerRepository(
    private val store: KeyValueStore,
    private val capabilities: MutableCapabilitiesState,
    /** Follows [KeyValueStore.changedElsewhere] while it lives; null to ignore other copies (tests that do not share the store). */
    scope: CoroutineScope? = null,
) : ServerRepository {
    private val state = MutableStateFlow(load())

    override val servers: StateFlow<List<SavedServer>> = state.asStateFlow()

    init {
        state.value.forEach(::announce)
        if (scope != null) {
            store.changedElsewhere
                .filter { it == null || it == INDEX || it.startsWith(ENTRY_PREFIX) }
                .onEach { reload() }
                .launchIn(scope)
        }
    }

    override fun save(server: SavedServer) {
        val isNew = state.value.none { it.ref == server.ref }
        store.put(entryKey(server.ref), json.encodeToString(StoredServer.serializer(), StoredServer.from(server)))
        state.update { list -> if (isNew) list + server else list.map { if (it.ref == server.ref) server else it } }
        if (isNew) writeIndex { refs -> if (server.ref in refs) refs else refs + server.ref }
        announce(server)
    }

    override fun remove(ref: ServerRef) {
        if (state.value.none { it.ref == ref }) return
        state.update { list -> list.filterNot { it.ref == ref } }
        writeIndex { refs -> refs - ref }
        store.remove(entryKey(ref))
        capabilities.remove(ref)
    }

    /** Reads the store again after another copy changed it: servers it added are announced, the ones it removed leave the capabilities. */
    private fun reload() {
        val loaded = load()
        val gone = state.value.map { it.ref } - loaded.map { it.ref }.toSet()
        state.value = loaded
        gone.forEach(capabilities::remove)
        loaded.forEach(::announce)
    }

    private fun announce(server: SavedServer) {
        if (capabilities.capabilities.value.status(server.ref) == null) capabilities.put(ServerStatus(server.ref))
    }

    /**
     * Writes the index as [change] makes it from the stored one, which another copy may have changed since this one last read it. Only
     * when nothing is stored (none saved yet, or storage that drops writes) does it start from this copy's list.
     */
    private fun writeIndex(change: (List<ServerRef>) -> List<ServerRef>) {
        val refs = storedRefs() ?: state.value.map { it.ref }
        store.put(INDEX, json.encodeToString(ListSerializer(StoredRef.serializer()), change(refs).map { StoredRef.from(it) }))
    }

    private fun storedRefs(): List<ServerRef>? =
        decodeOrNull(store.get(INDEX)) { json.decodeFromString(ListSerializer(StoredRef.serializer()), it) }
            ?.mapNotNull { stored -> decodeOrNull(stored) { it.toRef() } }

    private fun load(): List<SavedServer> {
        val refs = storedRefs() ?: return emptyList()
        return refs.mapNotNull { ref ->
            decodeOrNull(store.get(entryKey(ref))) { json.decodeFromString(StoredServer.serializer(), it).toSavedServer() }
                ?.takeIf { it.ref == ref }
        }
    }

    private fun <T : Any, R> decodeOrNull(value: T?, decode: (T) -> R): R? {
        if (value == null) return null
        return try {
            decode(value)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val INDEX = "servers"
        const val ENTRY_PREFIX = "server."
        val json = Json { ignoreUnknownKeys = true }

        fun entryKey(ref: ServerRef) = "$ENTRY_PREFIX${ref.service.value}.${ref.key}"
    }
}

@Serializable
private data class StoredRef(val service: String, val key: String) {
    fun toRef() = ServerRef(ServiceType(service), key)

    companion object {
        fun from(ref: ServerRef) = StoredRef(ref.service.value, ref.key)
    }
}

@Serializable
private data class StoredServer(
    val service: String,
    val key: String,
    val serverId: String,
    val name: String,
    val addresses: List<StoredAddress>,
) {
    fun toSavedServer() = SavedServer(ServerRef(ServiceType(service), key), serverId, name, addresses.map { it.toAddress() })

    companion object {
        fun from(server: SavedServer) = StoredServer(
            service = server.ref.service.value,
            key = server.ref.key,
            serverId = server.serverId,
            name = server.name,
            addresses = server.addresses.map { StoredAddress(it.url, it.scope.name) },
        )
    }
}

@Serializable
private data class StoredAddress(val url: String, val scope: String) {
    /**
     * The scope is computed from the URL again, so an address saved under older rules gets today's ([ServerUrl.scope]). It is still
     * written for older versions. Only a URL that no longer parses keeps the stored one; an unknown one counts as internet, the stricter.
     */
    fun toAddress() = ServerAddress(
        url,
        ServerUrl.parse(url)?.scope ?: AddressScope.entries.firstOrNull { it.name == scope } ?: AddressScope.Internet,
    )
}
