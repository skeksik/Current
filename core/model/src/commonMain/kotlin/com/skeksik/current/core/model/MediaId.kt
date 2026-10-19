package com.skeksik.current.core.model

/**
 * Which server an item came from plus its id there. Two ids are the same item only if both parts are equal: the same title from
 * Jellyfin, Seerr and Stash has three ids, joined later through provider ids or the server's own links (docs/architecture.md).
 *
 * [id] is opaque and unique within its server (Jellyfin: 32 hex characters). A service whose ids are only unique per kind, such as
 * TMDB ids in Seerr, puts the kind into [id] in its data layer.
 */
data class MediaId(val server: ServerRef, val id: String) {
    init {
        require(id.isNotBlank()) { "MediaId id must not be blank" }
    }

    val service: ServiceType get() = server.service
}
