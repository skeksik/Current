package com.skeksik.current.data.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Shapes from the Jellyfin 12.2 OpenAPI spec (docs/platform-notes.md, "Jellyfin 12.2: sign-in"). Everything is nullable there.

@Serializable
internal data class AuthenticateByNameDto(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

@Serializable
internal data class QuickConnectSecretDto(@SerialName("Secret") val secret: String)

@Serializable
internal data class AuthenticationResultDto(
    @SerialName("User") val user: UserDto? = null,
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
)

@Serializable
internal data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("Policy") val policy: UserPolicyDto? = null,
)

@Serializable
internal data class UserPolicyDto(@SerialName("IsAdministrator") val isAdministrator: Boolean = false)

@Serializable
internal data class QuickConnectResultDto(
    @SerialName("Authenticated") val authenticated: Boolean = false,
    @SerialName("Secret") val secret: String = "",
    @SerialName("Code") val code: String = "",
)
