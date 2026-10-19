package com.skeksik.current.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerUrlTest {
    @Test
    fun keepsPortAndPathPrefix() {
        assertEquals("http://192.168.1.10:8096/jellyfin", ServerUrl.parse(" http://192.168.1.10:8096/jellyfin/ ").toString())
    }

    @Test
    fun dropsDoubledAndTrailingSlashes() {
        assertEquals("https://media.example.com/a/b", ServerUrl.parse("https://media.example.com//a//b//").toString())
        assertEquals(ServerUrl.parse("https://media.example.com"), ServerUrl.parse("https://media.example.com/"))
    }

    @Test
    fun knowsHttps() {
        assertTrue(ServerUrl.parse("https://media.example.com")!!.isHttps)
        assertFalse(ServerUrl.parse("http://media.example.com")!!.isHttps)
    }

    @Test
    fun rejectsWhatIsNotAServerAddress() {
        listOf(
            "",
            "media.example.com:8096",
            "ftp://media.example.com",
            "https://user:pass@media.example.com",
            "https://media.example.com/?ApiKey=1",
            "https://media.example.com/#top",
        ).forEach { assertNull(ServerUrl.parse(it), it) }
    }
}
