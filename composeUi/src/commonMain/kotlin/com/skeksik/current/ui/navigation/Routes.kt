package com.skeksik.current.ui.navigation

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import kotlinx.serialization.Serializable

// Pages that another feature may open. Features never import each other (CLAUDE.md), so these routes live in the shared contract and the
// feature that owns each page registers it.

/** Settings > Servers > Add server. */
@Serializable
data object AddServerRoute

/** Sign in to a saved server; [name] is shown while the page loads. */
@Serializable
data class SignInRoute(val service: String, val key: String, val name: String) {
    constructor(server: ServerRef, name: String) : this(server.service.value, server.key, name)

    val server: ServerRef get() = ServerRef(ServiceType(service), key)
}
