package com.skeksik.current.core.playback

/**
 * What one engine knows about the dynamic ranges of one video codec. Each engine fills it from its own capability source (the web
 * from browser probes); [VideoRanges.rangeTypes] turns it into the server's terms, the same way for every platform.
 */
data class RangeSupport(
    /** HDR reaches the screen: an HDR display and an HDR output path. Without it the server tone-maps everything HDR. */
    val hdrOutput: Boolean,
    /** Decodes HDR10 (PQ, BT.2020) in this codec. */
    val hdr10: Boolean,
    /** Decodes HLG in this codec. */
    val hlg: Boolean,
    /** Dolby Vision profiles the engine decodes as Dolby Vision in this codec: 5 and 8 (HEVC), 10 (AV1), 7 (dual layer). */
    val doviProfiles: Set<Int> = emptySet(),
    /**
     * The engine plays a Dolby Vision stream with a compatible base layer (SDR, HDR10, HLG) as that base layer, ignoring the
     * Dolby Vision metadata. Without it, such streams go to the server unless their profile is in [doviProfiles].
     */
    val baseLayerFallback: Boolean,
)

/**
 * Jellyfin's `VideoRangeType`s an engine accepts for one codec. Jellyfin 12.2 labels Dolby Vision streams by profile and base layer
 * (`MediaStream.GetVideoColorRange`): profile 5 and profile 10 without a base layer are `DOVI`; profile 8 and 10 with an HDR10, SDR or
 * HLG base layer are `DOVIWithHDR10`, `DOVIWithSDR`, `DOVIWithHLG` (`DOVIWithHDR10Plus` when the base layer carries HDR10+);
 * profile 7 is `DOVIWithEL` (`DOVIWithELHDR10Plus`); out-of-spec files are `DOVIInvalid`, which no engine accepts here.
 * Its condition matching accepts `HDR10Plus` wherever `HDR10` is allowed, and nothing else in place of another range type.
 */
object VideoRanges {
    fun rangeTypes(s: RangeSupport): List<String> = buildList {
        add("SDR")
        if (s.baseLayerFallback) add("DOVIWithSDR")
        if (s.hdrOutput && s.hdr10) {
            // HDR10+ plays as static HDR10: no engine keeps its dynamic metadata (ADR 0007).
            addAll(listOf("HDR10", "HDR10Plus"))
            if (s.baseLayerFallback) addAll(listOf("DOVIWithHDR10", "DOVIWithHDR10Plus"))
        }
        if (s.hdrOutput && s.hlg) {
            add("HLG")
            if (s.baseLayerFallback) add("DOVIWithHLG")
        }
        if (s.hdrOutput) {
            val withBaseLayer = listOf("DOVIWithHDR10", "DOVIWithHDR10Plus", "DOVIWithHLG", "DOVIWithSDR")
            if (5 in s.doviProfiles) add("DOVI")
            if (8 in s.doviProfiles) addAll(withBaseLayer)
            if (10 in s.doviProfiles) addAll(listOf("DOVI") + withBaseLayer)
            if (7 in s.doviProfiles) addAll(listOf("DOVIWithEL", "DOVIWithELHDR10Plus"))
        }
    }.distinct()
}
