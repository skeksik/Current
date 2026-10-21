package com.skeksik.current.data.jellyfin

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.FixedServerAddress
import com.skeksik.current.core.network.ServerClients
import com.skeksik.current.core.network.ServerIdentity
import com.skeksik.current.core.network.ServerProbe
import com.skeksik.current.core.network.ServerUrl
import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identifies a Jellyfin server by `GET /System/Info/Public`, which needs no credentials (docs/platform-notes.md, "Jellyfin 12.2: local
 * discovery and server identity"). Its `Id` is the server's own id, kept across restarts. `LocalAddress` is not used: behind a
 * reverse proxy it may be an internal address.
 */
internal class JellyfinServerProbe(
    private val clients: ServerClients,
    override val timeout: Duration = 5.seconds,
) : ServerProbe {
    override val service: ServiceType = ServiceType.jellyfin

    /** Jellyfin's default HTTP port. */
    override val defaultPort: Int = 8096

    override suspend fun identify(server: ServerRef, address: ServerUrl): ServerIdentity =
        clients.create(server, FixedServerAddress(address)).use { client ->
            val info = client.request {
                get("System/Info/Public") { timeout { requestTimeoutMillis = timeout.inWholeMilliseconds } }.body<PublicSystemInfo>()
            }
            if (info.id.isBlank()) throw ServiceException(server, ServiceErrorKind.InvalidResponse, detail = "no server id")
            ServerIdentity(serverId = info.id, name = info.serverName ?: "", version = info.version)
        }
}

@Serializable
private data class PublicSystemInfo(
    @SerialName("Id") val id: String,
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)
