package com.skeksik.current.core.model

/**
 * What an item is. Open, not an enum (ADR 0008): items of a kind core does not know (a modified server, an extension) keep their
 * value unchanged, so an extension can render them; renderers are looked up by kind. Data layers map their service's own type names
 * onto the [known] values and pass anything else through as it came. Extensions should prefix their own kinds (`"example.clip"`)
 * so they cannot collide with a service's names.
 */
data class MediaKind(val value: String) {
    init {
        require(value.isNotBlank()) { "MediaKind must not be blank" }
    }

    val isKnown: Boolean get() = this in known

    companion object {
        val movie = MediaKind("movie")
        val series = MediaKind("series")
        val season = MediaKind("season")
        val episode = MediaKind("episode")
        /** A video that is neither a movie nor an episode: home videos, extras. */
        val video = MediaKind("video")
        val collection = MediaKind("collection")
        val playlist = MediaKind("playlist")
        val person = MediaKind("person")

        val known: List<MediaKind> = listOf(movie, series, season, episode, video, collection, playlist, person)
    }
}
