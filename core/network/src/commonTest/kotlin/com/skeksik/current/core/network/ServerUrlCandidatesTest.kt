package com.skeksik.current.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerUrlCandidatesTest {
    private fun candidates(text: String, port: Int? = 8096) = ServerUrl.candidates(text, port).map { it.toString() }

    @Test
    fun aSchemeIsUsedAsTyped() {
        assertEquals(listOf("http://192.168.1.10:8096"), candidates("http://192.168.1.10:8096"))
    }

    @Test
    fun withoutSchemeOrPortHttpsComesFirstThenHttpThenTheDefaultPort() {
        assertEquals(
            listOf("https://media.example.com/jellyfin", "http://media.example.com/jellyfin", "http://media.example.com:8096/jellyfin"),
            candidates(" media.example.com/jellyfin "),
        )
    }

    @Test
    fun aTypedPortIsKept() {
        assertEquals(listOf("https://192.168.1.10:8096", "http://192.168.1.10:8096"), candidates("192.168.1.10:8096"))
    }

    @Test
    fun noDefaultPortMeansTwoCandidates() {
        assertEquals(listOf("https://media.lan", "http://media.lan"), candidates("media.lan", port = null))
    }

    @Test
    fun nothingUsableGivesNone() {
        assertEquals(emptyList(), candidates("   "))
        assertEquals(emptyList(), candidates("ftp://media.example.com"))
    }
}
