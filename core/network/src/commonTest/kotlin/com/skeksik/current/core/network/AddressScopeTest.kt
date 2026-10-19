package com.skeksik.current.core.network

import com.skeksik.current.core.model.AddressScope
import kotlin.test.Test
import kotlin.test.assertEquals

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
        ).forEach { assertEquals(AddressScope.Internet, scopeOf(it), it) }
    }
}
