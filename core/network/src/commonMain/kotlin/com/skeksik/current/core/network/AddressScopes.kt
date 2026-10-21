package com.skeksik.current.core.network

import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer

/** Whether this address is on a local or private network (ADR 0016). Decided from the host alone; nothing is resolved. */
val ServerUrl.scope: AddressScope get() = addressScopeOf(url.host)

/**
 * Whether reaching this address presumably needs local network access (ADR 0020): a [AddressScope.Local] address, except an overlay
 * VPN (Tailscale's `100.64.0.0/10`, `fd7a:115c:a1e0::/48` and `.ts.net`), which the permission most likely does not cover. Not
 * verified on a device.
 */
internal val ServerUrl.needsLocalNetworkAccess: Boolean
    get() = Host.of(url.host).let { it.isLocal() && !it.isOverlayVpn() }

/**
 * Whether any address of this server presumably needs local network access: the one rule for asking (ADR 0020) and for roaming's
 * skipping (ADR 0019). Computed from the URLs, so a server reached only through an overlay VPN is never asked about.
 */
val SavedServer.needsLocalNetworkAccess: Boolean
    get() = addresses.any { ServerUrl.parse(it.url)?.needsLocalNetworkAccess == true }

internal fun addressScopeOf(host: String): AddressScope = if (Host.of(host).isLocal()) AddressScope.Local else AddressScope.Internet

/**
 * A host, normalised and parsed once: [ipv4] for an IPv4 literal, or for an IPv4-mapped IPv6 one (`::ffff:192.168.1.10`), which is
 * classified by its IPv4 part.
 */
private class Host(val name: String, val ipv4: List<Int>?) {
    fun isLocal(): Boolean = when {
        name == "localhost" -> true
        ipv4 != null -> isLocalIpv4(ipv4)
        ':' in name -> isLocalIpv6(name)
        // A single label (`nas`) only resolves on the local network.
        '.' !in name -> true
        else -> localSuffixes.any { name.endsWith(it) }
    }

    fun isOverlayVpn(): Boolean = when {
        ipv4 != null -> ipv4[0] == 100 && ipv4[1] in 64..127
        ':' in name -> isTailscaleIpv6(name)
        else -> name.endsWith(".ts.net")
    }

    companion object {
        fun of(host: String): Host {
            val name = host.lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
            return Host(name, ipv4(name) ?: mappedIpv4(name))
        }
    }
}

private val localSuffixes = listOf(".local", ".lan", ".home.arpa", ".localhost", ".internal", ".ts.net")

private fun ipv4(name: String): List<Int>? {
    val parts = name.split('.')
    if (parts.size != 4) return null
    return parts.map { part -> part.toIntOrNull()?.takeIf { part.isNotEmpty() && it in 0..255 } ?: return null }
}

/** The IPv4 part of `::ffff:a.b.c.d` or `::ffff:xxxx:xxxx` (also written out as `0:0:0:0:0:ffff:`), or null. */
private fun mappedIpv4(name: String): List<Int>? {
    val rest = listOf("::ffff:", "0:0:0:0:0:ffff:").firstNotNullOfOrNull { prefix -> name.removePrefix(prefix).takeIf { it != name } }
        ?: return null
    ipv4(rest)?.let { return it }
    val hextets = rest.split(':')
    if (hextets.size != 2) return null
    val (high, low) = hextets.map { hextet -> hextet.takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return null }
    return listOf(high shr 8, high and 0xff, low shr 8, low and 0xff)
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

/** Tailscale's IPv6 prefix, `fd7a:115c:a1e0::/48` (docs/platform-notes.md). The first three hextets, written without leading zeros. */
private fun isTailscaleIpv6(address: String): Boolean {
    val hextets = address.substringBefore('%').split(':')
    if (hextets.size < 4) return false
    return hextets.take(3).map { it.toIntOrNull(16) } == listOf(0xfd7a, 0x115c, 0xa1e0)
}
