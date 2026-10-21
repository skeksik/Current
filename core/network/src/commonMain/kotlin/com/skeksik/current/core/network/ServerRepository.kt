package com.skeksik.current.core.network

import com.skeksik.current.core.database.KeyValueStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import kotlin.random.Random
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
 */
internal class StoredServerRepository(
    private val store: KeyValueStore,
    private val capabilities: MutableCapabilitiesState,
) : ServerRepository {
    private val state = MutableStateFlow(load())

    override val servers: StateFlow<List<SavedServer>> = state.asStateFlow()

    init {
        state.value.forEach(::announce)
    }

    override fun save(server: SavedServer) {
        val isNew = state.value.none { it.ref == server.ref }
        store.put(entryKey(server.ref), json.encodeToString(StoredServer.serializer(), StoredServer.from(server)))
        state.update { list -> if (isNew) list + server else list.map { if (it.ref == server.ref) server else it } }
        if (isNew) writeIndex()
        announce(server)
    }

    override fun remove(ref: ServerRef) {
        if (state.value.none { it.ref == ref }) return
        state.update { list -> list.filterNot { it.ref == ref } }
        writeIndex()
        store.remove(entryKey(ref))
        capabilities.remove(ref)
    }

    private fun announce(server: SavedServer) {
        if (capabilities.capabilities.value.status(server.ref) == null) capabilities.put(ServerStatus(server.ref))
    }

    private fun writeIndex() =
        store.put(INDEX, json.encodeToString(ListSerializer(StoredRef.serializer()), state.value.map { StoredRef.from(it.ref) }))

    private fun load(): List<SavedServer> {
        val refs = decodeOrNull(store.get(INDEX)) { json.decodeFromString(ListSerializer(StoredRef.serializer()), it) } ?: return emptyList()
        return refs.mapNotNull { stored ->
            val ref = decodeOrNull(stored) { it.toRef() } ?: return@mapNotNull null
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
        val json = Json { ignoreUnknownKeys = true }

        fun entryKey(ref: ServerRef) = "server.${ref.service.value}.${ref.key}"
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
