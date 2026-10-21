package com.skeksik.current.core.network

import com.skeksik.current.core.model.AddressScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddressScopeTest {
    private fun scopeOf(url: String) = ServerUrl.parse(url)!!.scope

    @Test
    fun privateAndOverlayNetworksAreLocal() {
        listOf(
            "http://192.168.1.10:8096",
            "http://10.0.0.5",
            "http://172.16.0.1",
            "http://172.31.255.255",
            "http://127.0.0.1:8096",
            "http://169.254.10.10",
            "http://100.64.0.1",
            "http://100.127.255.255",
            "http://localhost:8096",
            "http://nas:8096",
            "http://media.lan:8096",
            "http://media.local",
            "http://media.home.arpa",
            "https://media.example.ts.net",
            "http://[::1]:8096",
            "http://[fd12:3456::1]:8096",
            "http://[fe80::1]",
            "http://[fd7a:115c:a1e0::1]:8096",
            "http://[::ffff:192.168.1.10]:8096",
            "http://[::ffff:c0a8:10a]:8096",
            "http://[::ffff:100.64.0.1]",
        ).forEach { assertEquals(AddressScope.Local, scopeOf(it), it) }
    }

    @Test
    fun everythingElseIsInternet() {
        listOf(
            "https://media.example.com",
            "http://203.0.113.7:8096",
            "http://172.32.0.1",
            "http://100.128.0.1",
            "http://192.169.1.1",
            "http://[2001:db8::1]",
            "http://local.example.com",
            "http://[::ffff:203.0.113.7]",
            "http://[::ffff:cb00:7107]",
        ).forEach { assertEquals(AddressScope.Internet, scopeOf(it), it) }
    }

    @Test
    fun overlayVpnAddressesNeedNoLocalNetworkAccess() {
        listOf(
            "http://100.64.0.1",
            "http://100.127.255.255",
            "https://media.example.ts.net",
            "https://media.example.ts.net.",
            "http://[fd7a:115c:a1e0::1]:8096",
            "http://[fd7a:115c:a1e0:ab12:4843:cd96:6258:b240]",
            "http://[::ffff:100.100.1.2]",
        ).forEach { assertFalse(ServerUrl.parse(it)!!.needsLocalNetworkAccess, it) }
    }

    @Test
    fun otherLocalAddressesNeedLocalNetworkAccess() {
        listOf(
            "http://192.168.1.10:8096",
            "http://nas:8096",
            "http://media.lan",
            "http://[fd12:3456::1]",
            "http://[fd7a:115c:a1e1::1]",
            "http://[fd7a:115c::1]",
            "http://[::ffff:192.168.1.10]",
            "http://[::ffff:c0a8:10a]",
        ).forEach { assertTrue(ServerUrl.parse(it)!!.needsLocalNetworkAccess, it) }
        assertFalse(ServerUrl.parse("https://media.example.com")!!.needsLocalNetworkAccess)
    }
}
