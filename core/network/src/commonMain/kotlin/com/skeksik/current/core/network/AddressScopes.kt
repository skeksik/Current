package com.skeksik.current.core.network

import com.skeksik.current.core.model.AddressScope

/** Whether this address is on a local or private network (ADR 0016). Decided from the host alone; nothing is resolved. */
val ServerUrl.scope: AddressScope get() = addressScopeOf(url.host)

private val localSuffixes = listOf(".local", ".lan", ".home.arpa", ".localhost", ".internal", ".ts.net")

internal fun addressScopeOf(host: String): AddressScope {
    val name = host.lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
    val local = when {
        name == "localhost" -> true
        ipv4(name) != null -> isLocalIpv4(ipv4(name)!!)
        ':' in name -> isLocalIpv6(name)
        // A single label (`nas`) only resolves on the local network.
        '.' !in name -> true
        else -> localSuffixes.any { name.endsWith(it) }
    }
    return if (local) AddressScope.Local else AddressScope.Internet
}

private fun ipv4(name: String): List<Int>? {
    val parts = name.split('.')
    if (parts.size != 4) return null
    return parts.map { part -> part.toIntOrNull()?.takeIf { part.isNotEmpty() && it in 0..255 } ?: return null }
}

/** Private (RFC 1918), loopback, link-local, and shared address space (100.64.0.0/10, used by Tailscale). */
private fun isLocalIpv4(octets: List<Int>): Boolean {
    val (a, b) = octets
    return a == 10 || a == 127 ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254) ||
        (a == 100 && b in 64..127)
}

/** Loopback, unique local (fc00::/7) and link-local (fe80::/10). */
private fun isLocalIpv6(address: String): Boolean {
    val name = address.substringBefore('%')
    if (name == "::1") return true
    val first = name.substringBefore(':').padStart(4, '0').toIntOrNull(16) ?: return false
    return first and 0xfe00 == 0xfc00 || first and 0xffc0 == 0xfe80
}
