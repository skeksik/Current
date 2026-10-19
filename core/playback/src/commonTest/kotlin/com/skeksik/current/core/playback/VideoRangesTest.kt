package com.skeksik.current.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoRangesTest {
    private fun ranges(
        hdrOutput: Boolean = true,
        hdr10: Boolean = false,
        hlg: Boolean = false,
        dovi: Set<Int> = emptySet(),
        baseLayer: Boolean = true,
    ) = VideoRanges.rangeTypes(RangeSupport(hdrOutput, hdr10, hlg, dovi, baseLayer))

    @Test
    fun withoutHdrOutputOnlySdrAndTheSdrBaseLayer() {
        assertEquals(listOf("SDR", "DOVIWithSDR"), ranges(hdrOutput = false, hdr10 = true, hlg = true, dovi = setOf(5, 7, 8, 10)))
        assertEquals(listOf("SDR"), ranges(hdrOutput = false, baseLayer = false))
    }

    @Test
    fun hdr10PlusAndItsDolbyVisionBaseLayerGoWithHdr10() {
        assertEquals(listOf("SDR", "DOVIWithSDR", "HDR10", "HDR10Plus", "DOVIWithHDR10", "DOVIWithHDR10Plus"), ranges(hdr10 = true))
        assertEquals(listOf("SDR", "DOVIWithSDR", "HLG", "DOVIWithHLG"), ranges(hlg = true))
    }

    @Test
    fun withoutBaseLayerFallbackDolbyVisionNeedsItsOwnDecoder() {
        assertEquals(listOf("SDR", "HDR10", "HDR10Plus", "HLG"), ranges(hdr10 = true, hlg = true, baseLayer = false))
        assertEquals(
            listOf("SDR", "DOVIWithHDR10", "DOVIWithHDR10Plus", "DOVIWithHLG", "DOVIWithSDR"),
            ranges(dovi = setOf(8), baseLayer = false),
        )
    }

    @Test
    fun eachDolbyVisionProfileAddsWhatJellyfinLabelsIt() {
        assertEquals(listOf("SDR", "DOVIWithSDR", "DOVI"), ranges(dovi = setOf(5)))
        val av1 = ranges(dovi = setOf(10), baseLayer = false)
        assertEquals(setOf("SDR", "DOVI", "DOVIWithHDR10", "DOVIWithHDR10Plus", "DOVIWithHLG", "DOVIWithSDR"), av1.toSet())
        val dualLayer = ranges(dovi = setOf(7))
        assertTrue(dualLayer.containsAll(listOf("DOVIWithEL", "DOVIWithELHDR10Plus")))
        assertFalse("DOVI" in dualLayer)
    }

    @Test
    fun outOfSpecDolbyVisionAlwaysGoesToTheServer() {
        val all = ranges(hdr10 = true, hlg = true, dovi = setOf(5, 7, 8, 10))
        assertFalse("DOVIInvalid" in all)
        assertEquals(all.size, all.toSet().size)
    }
}
