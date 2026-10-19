package com.skeksik.current.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilitiesTest {
    private val jellyfin = ServerRef(ServiceType.jellyfin, "server-1")
    private val seerr = ServerRef(ServiceType.seerr, "server-2")
    private val stash = ServerRef(ServiceType.stash, "server-3")

    @Test
    fun nothingIsAvailableWithoutServers() {
        val none = Capabilities()
        assertTrue(none.isEmpty)
        ServiceType.known.forEach { assertFalse(none.isAvailable(it)) }
    }

    @Test
    fun aServiceIsAvailableOnlyWhenSignedIn() {
        val capabilities = Capabilities(listOf(ServerStatus(jellyfin, reachable = true), ServerStatus(stash, signedIn = true)))
        assertFalse(capabilities.isEmpty)
        assertFalse(capabilities.isAvailable(ServiceType.jellyfin))
        assertTrue(capabilities.isAvailable(ServiceType.stash))
        assertFalse(capabilities.isAvailable(ServiceType.seerr))
    }

    @Test
    fun anUnreachableServerStaysAvailable() {
        val capabilities = Capabilities(listOf(ServerStatus(jellyfin, signedIn = true, reachable = false)))
        assertTrue(capabilities.isAvailable(ServiceType.jellyfin))
    }

    @Test
    fun permissionsComeFromSignedInUsersOfThatService() {
        val requester = User("1", seerr, "Requester", setOf(Permission.request))
        val capabilities = Capabilities(listOf(ServerStatus(seerr, signedIn = true, user = requester)))
        assertTrue(capabilities.can(Permission.request, ServiceType.seerr))
        assertFalse(capabilities.can(Permission.request4k, ServiceType.seerr))
        assertFalse(capabilities.can(Permission.request, ServiceType.jellyfin))
    }

    @Test
    fun anyServerOfTheServiceCanGrantAPermission() {
        val otherSeerr = ServerRef(ServiceType.seerr, "server-4")
        val capabilities = Capabilities(
            listOf(
                ServerStatus(seerr, signedIn = true, user = User("1", seerr, "Viewer")),
                ServerStatus(otherSeerr, signedIn = true, user = User("1", otherSeerr, "Admin", setOf(Permission.manageRequests))),
            ),
        )
        assertTrue(capabilities.can(Permission.manageRequests, ServiceType.seerr))
    }

    @Test
    fun featuresCountOnlyOnSignedInServers() {
        val segments = ServerFeature("mediaSegments")
        val signedOut = Capabilities(listOf(ServerStatus(jellyfin, features = setOf(segments))))
        assertFalse(signedOut.has(segments, ServiceType.jellyfin))
        val signedIn = signedOut.with(ServerStatus(jellyfin, signedIn = true, features = setOf(segments)))
        assertTrue(signedIn.has(segments, ServiceType.jellyfin))
        assertFalse(signedIn.has(ServerFeature("other"), ServiceType.jellyfin))
    }

    @Test
    fun withAddsOrReplacesAndWithoutRemoves() {
        val added = Capabilities().with(ServerStatus(jellyfin)).with(ServerStatus(seerr))
        assertEquals(listOf(jellyfin, seerr), added.servers.map { it.server })

        val replaced = added.with(ServerStatus(jellyfin, signedIn = true))
        assertEquals(listOf(jellyfin, seerr), replaced.servers.map { it.server })
        assertEquals(true, replaced.status(jellyfin)?.signedIn)

        val removed = replaced.without(jellyfin)
        assertNull(removed.status(jellyfin))
        assertEquals(listOf(seerr), removed.servers.map { it.server })
    }

    @Test
    fun oneStatusPerServer() {
        assertFailsWith<IllegalArgumentException> { Capabilities(listOf(ServerStatus(jellyfin), ServerStatus(jellyfin))) }
    }

    @Test
    fun aUserMustBelongToItsServerAndBeSignedIn() {
        assertFailsWith<IllegalArgumentException> { ServerStatus(jellyfin, signedIn = true, user = User("1", seerr, "Elsewhere")) }
        assertFailsWith<IllegalArgumentException> { ServerStatus(jellyfin, user = User("1", jellyfin, "Signed out")) }
    }

    @Test
    fun unknownFeaturesKeepTheirValue() {
        val feature = ServerFeature("SomePlugin")
        assertFalse(feature.isKnown)
        assertEquals("SomePlugin", feature.value)
        assertFailsWith<IllegalArgumentException> { ServerFeature(" ") }
    }
}
