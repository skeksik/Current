package com.skeksik.current.core.model

/**
 * What the app knows about every configured server, read by features to hide what is unavailable (ADR 0008, ADR 0014). No feature
 * assumes a service exists: it asks here.
 *
 * A service is available when one of its servers is signed in. Reachability does not hide anything: a server that drops off the
 * network keeps its features visible with an offline state, so tabs do not come and go with the connection.
 */
data class Capabilities(val servers: List<ServerStatus> = emptyList()) {
    init {
        require(servers.distinctBy { it.server }.size == servers.size) { "Capabilities hold one status per server" }
    }

    /** No server is configured: the app shows its setup. */
    val isEmpty: Boolean get() = servers.isEmpty()

    fun status(server: ServerRef): ServerStatus? = servers.firstOrNull { it.server == server }

    /** The signed-in servers of [service]. */
    fun available(service: ServiceType): List<ServerStatus> = servers.filter { it.server.service == service && it.signedIn }

    fun isAvailable(service: ServiceType): Boolean = available(service).isNotEmpty()

    /** Some signed-in server of [service] lets its user do [permission]. */
    fun can(permission: Permission, service: ServiceType): Boolean = available(service).any { it.user?.can(permission) == true }

    /** Some signed-in server of [service] reports [feature]. */
    fun has(feature: ServerFeature, service: ServiceType): Boolean = available(service).any { feature in it.features }

    /** These capabilities with [status] added, or replacing the status of the same server. */
    fun with(status: ServerStatus): Capabilities =
        if (status(status.server) == null) Capabilities(servers + status)
        else Capabilities(servers.map { if (it.server == status.server) status else it })

    /** These capabilities without [server]. */
    fun without(server: ServerRef): Capabilities = Capabilities(servers.filterNot { it.server == server })
}

/**
 * One configured server as the app last saw it. Written by server setup and sign-in; [reachable] by the checks and requests that talk
 * to it.
 */
data class ServerStatus(
    val server: ServerRef,
    /** Signed in: a Jellyfin or Seerr session, or a Stash API key. */
    val signedIn: Boolean = false,
    /** Null until the first check; false while the server cannot be reached. Drives offline states, never hides features. */
    val reachable: Boolean? = null,
    /** The signed-in user. Null when signed out and for services without users (Stash). */
    val user: User? = null,
    /** The version the server reports, as it reports it. */
    val version: String? = null,
    /** Switches the server reports, such as an installed plugin. */
    val features: Set<ServerFeature> = emptySet(),
) {
    init {
        require(user == null || signedIn) { "A user means signed in" }
        require(user == null || user.server == server) { "The user belongs to another server" }
    }
}

/**
 * Something a server can do beyond its service's basics, such as a plugin it has installed or a setting it has on. Open like
 * [MediaKind]: the data layer maps what its server reports onto these values and passes anything else through.
 */
data class ServerFeature(val value: String) {
    init {
        require(value.isNotBlank()) { "ServerFeature must not be blank" }
    }

    val isKnown: Boolean get() = this in known

    companion object {
        /** None yet: each one arrives with the data layer that reads it. */
        val known: List<ServerFeature> = emptyList()
    }
}
