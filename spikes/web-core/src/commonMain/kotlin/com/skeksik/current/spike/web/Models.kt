package com.skeksik.current.spike.web

/** A signed-in Jellyfin user. [viaApiKey] marks a session seeded with an API key (smoke tests): it is never logged out on the server. */
data class Session(
    val serverUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
    val viaApiKey: Boolean = false,
)

data class Library(val id: String, val name: String, val imageUrl: String?)

data class MediaItem(
    val id: String,
    val name: String,
    val type: String,
    val year: Int?,
    val overview: String?,
    val imageUrl: String?,
    /** Width / height of the poster; 2/3 when the server does not say. */
    val imageAspectRatio: Double,
    val isFolder: Boolean,
    val isPlayable: Boolean,
    val runtimeSeconds: Double?,
    val seriesName: String?,
)

data class Track(val index: Int, val label: String, val isDefault: Boolean)

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
    data class Failed(val message: String) : LoadState<Nothing>
}

/** `1:02:03` or `2:03`. */
fun formatTime(seconds: Double): String {
    val total = if (seconds.isNaN() || seconds < 0) 0L else seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = (total % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$s" else "$m:$s"
}
