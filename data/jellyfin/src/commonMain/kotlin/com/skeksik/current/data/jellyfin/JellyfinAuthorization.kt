package com.skeksik.current.data.jellyfin

import com.skeksik.current.core.database.DeviceIdentity
import com.skeksik.current.core.model.AppInfo
import com.skeksik.current.core.network.RequestAuth
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter

/**
 * Jellyfin's only accepted auth header since 12.0 (docs/platform-notes.md, "Jellyfin 12.x"):
 * `MediaBrowser Client="Current", Device="…", DeviceId="…", Version="…", Token="…"`, key names case-sensitive, values quoted and
 * URL-encoded. Without [token] (sign-in, Quick Connect) the header still names the client and device, which the server requires.
 */
internal class JellyfinAuthorization(
    private val device: DeviceIdentity,
    private val token: () -> String?,
) : RequestAuth {
    override suspend fun authorize(request: HttpRequestBuilder) {
        request.header(HttpHeaders.Authorization, headerValue(device, token()))
    }

    companion object {
        fun headerValue(device: DeviceIdentity, token: String?): String = buildList {
            add("Client" to AppInfo.NAME)
            add("Device" to device.deviceName)
            add("DeviceId" to device.deviceId)
            add("Version" to AppInfo.VERSION)
            if (token != null) add("Token" to token)
        }.joinToString(", ", prefix = "MediaBrowser ") { (key, value) -> "$key=\"${value.encodeURLParameter()}\"" }
    }
}
