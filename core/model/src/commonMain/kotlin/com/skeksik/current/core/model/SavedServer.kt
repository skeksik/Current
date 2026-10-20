package com.skeksik.current.core.model

/**
 * A server the user added (ADR 0016). [serverId] is the id the server itself reported when it was added (Jellyfin's `Id`); every
 * other address must report the same id before credentials go to it. [addresses] are in the user's priority order.
 */
data class SavedServer(
    val ref: ServerRef,
    val serverId: String,
    val name: String,
    val addresses: List<ServerAddress>,
) {
    init {
        require(serverId.isNotBlank()) { "SavedServer needs the id its server reported" }
        require(addresses.isNotEmpty()) { "SavedServer needs at least one address" }
        require(addresses.distinctBy { it.url }.size == addresses.size) { "SavedServer addresses must differ" }
    }
}

/** One way to reach a server: a normalised base URL (`core:network`'s `ServerUrl`) and whether it is on a local network. */
data class ServerAddress(val url: String, val scope: AddressScope)

/**
 * [Local]: a private network, the LAN or a private overlay such as Tailscale, where plain HTTP is accepted without a warning.
 * [Internet]: everything else.
 */
enum class AddressScope { Local, Internet }
