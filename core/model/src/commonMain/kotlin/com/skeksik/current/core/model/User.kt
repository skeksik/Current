package com.skeksik.current.core.model

/**
 * A signed-in user of one server. [id] is the server's own user id, unique only on [server]. Services without users (Stash: one
 * API key per server) have no [User].
 */
data class User(
    val id: String,
    val server: ServerRef,
    val name: String,
    val permissions: Set<Permission> = emptySet(),
    val avatar: Artwork? = null,
) {
    init {
        require(id.isNotBlank()) { "User id must not be blank" }
    }

    fun can(permission: Permission): Boolean = permission in permissions
}

/**
 * Something a user may do on their server, read by the capabilities state (ADR 0008) to hide what is not allowed. Open: an extension
 * adds its own (prefixed like its media kinds). Permissions do not imply each other here; the data layer maps its service's rules
 * (such as an admin flag that grants everything) onto the full set the user has.
 */
data class Permission(val value: String) {
    init {
        require(value.isNotBlank()) { "Permission must not be blank" }
    }

    val isKnown: Boolean get() = this in known

    companion object {
        /** Administers the server. */
        val admin = Permission("admin")
        /** Requests titles (Seerr). */
        val request = Permission("request")
        /** Requests titles in 4K (Seerr). */
        val request4k = Permission("request4k")
        /** Approves and declines other users' requests (Seerr). */
        val manageRequests = Permission("manageRequests")

        val known: List<Permission> = listOf(admin, request, request4k, manageRequests)
    }
}
