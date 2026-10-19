package com.skeksik.current.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class MediaIdTest {
    private val jellyfin = ServerRef(ServiceType.jellyfin, "server-1")
    private val otherJellyfin = ServerRef(ServiceType.jellyfin, "server-2")
    private val stash = ServerRef(ServiceType.stash, "server-1")
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun sameServerAndIdAreTheSameItem() {
        assertEquals(MediaId(jellyfin, id), MediaId(ServerRef(ServiceType("jellyfin"), "server-1"), id))
        assertEquals(MediaId(jellyfin, id).hashCode(), MediaId(jellyfin, id).hashCode())
        assertEquals(1, setOf(MediaId(jellyfin, id), MediaId(jellyfin, id)).size)
    }

    @Test
    fun theSameIdOnAnotherServiceOrServerIsAnotherItem() {
        assertNotEquals(MediaId(jellyfin, id), MediaId(stash, id))
        assertNotEquals(MediaId(jellyfin, id), MediaId(otherJellyfin, id))
        assertNotEquals(MediaId(jellyfin, id), MediaId(jellyfin, id.uppercase()))
    }

    @Test
    fun serviceComesFromTheServer() {
        assertEquals(ServiceType.stash, MediaId(stash, "42").service)
    }

    @Test
    fun blankPartsAreRejected() {
        assertFailsWith<IllegalArgumentException> { MediaId(jellyfin, " ") }
        assertFailsWith<IllegalArgumentException> { ServerRef(ServiceType.seerr, "") }
        assertFailsWith<IllegalArgumentException> { ServiceType("") }
    }

    @Test
    fun servicesAreOpen() {
        val added = ServiceType("example.local-files")
        assertFalse(added.isKnown)
        assertEquals(added, MediaId(ServerRef(added, "device"), "movies/a.mkv").service)
        assertEquals(listOf("jellyfin", "seerr", "stash"), ServiceType.known.map { it.value })
    }
}
