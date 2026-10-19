package com.skeksik.current.core.model

/**
 * The kind of service a server runs. Open, not an enum (ADR 0008): a later source (local files) adds its own value
 * without changing core, so code must not assume [known] is complete. Values are compared as given, never normalised.
 */
data class ServiceType(val value: String) {
    init {
        require(value.isNotBlank()) { "ServiceType must not be blank" }
    }

    val isKnown: Boolean get() = this in known

    companion object {
        val jellyfin = ServiceType("jellyfin")
        val seerr = ServiceType("seerr")
        val stash = ServiceType("stash")

        val known: List<ServiceType> = listOf(jellyfin, seerr, stash)
    }
}

/**
 * One configured server. [key] is opaque and assigned by the app when the server is added; it is not a hostname, URL or an id the
 * server reports, so it stays stable when the server moves and never leaks where it runs.
 */
data class ServerRef(val service: ServiceType, val key: String) {
    init {
        require(key.isNotBlank()) { "ServerRef key must not be blank" }
    }
}
