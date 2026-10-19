package com.skeksik.current.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ArtworkTest {
    @Test
    fun ratiosAreReduced() {
        assertEquals(AspectRatio.poster, AspectRatio.of(1000, 1500))
        assertEquals(AspectRatio.wide, AspectRatio.of(1920, 1080))
        assertEquals(AspectRatio.wide, AspectRatio.of(3840, 2160))
        assertEquals(AspectRatio.square, AspectRatio.of(512, 512))
        assertEquals(AspectRatio.poster.hashCode(), AspectRatio.of(680, 1020).hashCode())
        assertEquals("2:3", AspectRatio.of(1000, 1500).toString())
    }

    @Test
    fun aRatioThatDoesNotReduceToAConstantStaysExact() {
        val ratio = AspectRatio.of(1000, 1426)
        assertEquals(500, ratio.width)
        assertEquals(713, ratio.height)
        assertNotEquals(AspectRatio.poster, ratio)
    }

    @Test
    fun valueIsWidthOverHeight() {
        assertEquals(16.0 / 9.0, AspectRatio.wide.value)
        assertEquals(2.0 / 3.0, AspectRatio.poster.value)
        assertEquals(1.0, AspectRatio.square.value)
    }

    @Test
    fun sizesForAGivenSide() {
        assertEquals(300.0, AspectRatio.poster.heightFor(200.0))
        assertEquals(200.0, AspectRatio.poster.widthFor(300.0))
        assertEquals(180.0, AspectRatio.wide.heightFor(320.0))
        assertEquals(1920.0, AspectRatio.wide.widthFor(1080.0))
        val odd = AspectRatio.of(500, 713)
        assertEquals(250.0, odd.widthFor(odd.heightFor(250.0)), 1e-9)
    }

    @Test
    fun nonPositiveSidesAreRejected() {
        assertFailsWith<IllegalArgumentException> { AspectRatio.of(0, 9) }
        assertFailsWith<IllegalArgumentException> { AspectRatio.of(16, -9) }
    }

    @Test
    fun aspectRatioAndBlurHashAreOptional() {
        val logo = Artwork(ImageType.logo, "https://media.example/logo.png")
        assertNull(logo.aspectRatio)
        assertNull(logo.blurHash)
        assertEquals(
            Artwork(ImageType.primary, "https://media.example/p.jpg", AspectRatio.poster, "LEHV6nWB2yk8"),
            Artwork(ImageType("primary"), "https://media.example/p.jpg", AspectRatio.of(2, 3), "LEHV6nWB2yk8"),
        )
    }
}
