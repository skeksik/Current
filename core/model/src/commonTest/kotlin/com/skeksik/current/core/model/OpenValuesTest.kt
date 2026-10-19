package com.skeksik.current.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenValuesTest {
    @Test
    fun unknownKindsRoundTripUnchanged() {
        for (raw in listOf("Scene", "example.clip", "MusicVideo", "  padded  ", "Ünïcode")) {
            val kind = MediaKind(raw)
            assertEquals(raw, kind.value)
            assertEquals(kind, MediaKind(kind.value))
            assertFalse(kind.isKnown)
        }
    }

    @Test
    fun knownKindsAreComparedByValue() {
        assertEquals(MediaKind.movie, MediaKind("movie"))
        assertTrue(MediaKind("movie").isKnown)
        assertEquals(MediaKind.known.size, MediaKind.known.map { it.value }.toSet().size)
    }

    @Test
    fun kindsAreNotNormalised() {
        assertNotEquals(MediaKind.movie, MediaKind("Movie"))
        assertFalse(MediaKind("Movie").isKnown)
    }

    @Test
    fun aMapKeyedByKindFindsUnknownKinds() {
        val renderers = mapOf(MediaKind.movie to "movie card", MediaKind("example.clip") to "clip card")
        assertEquals("clip card", renderers[MediaKind("example.clip")])
        assertNull(renderers[MediaKind("example.other")])
    }

    @Test
    fun imageTypesAndPermissionsAreOpenToo() {
        assertEquals("Disc", ImageType("Disc").value)
        assertFalse(ImageType("Disc").isKnown)
        assertTrue(ImageType("primary").isKnown)
        assertEquals("example.reveal", Permission("example.reveal").value)
        assertFalse(Permission("example.reveal").isKnown)
        assertTrue(Permission("manageRequests").isKnown)
    }

    @Test
    fun blankValuesAreRejected() {
        assertFailsWith<IllegalArgumentException> { MediaKind("") }
        assertFailsWith<IllegalArgumentException> { ImageType(" ") }
        assertFailsWith<IllegalArgumentException> { Permission("") }
    }
}
