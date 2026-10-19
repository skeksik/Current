package com.skeksik.current.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UserTest {
    private val seerr = ServerRef(ServiceType.seerr, "server-1")

    @Test
    fun permissionsDoNotImplyEachOther() {
        val admin = User("1", seerr, "Admin", setOf(Permission.admin))
        assertTrue(admin.can(Permission.admin))
        assertFalse(admin.can(Permission.request))
    }

    @Test
    fun unknownPermissionsWorkLikeKnownOnes() {
        val requester = User("2", seerr, "Requester", setOf(Permission.request, Permission("example.reveal")))
        assertTrue(requester.can(Permission.request))
        assertTrue(requester.can(Permission("example.reveal")))
        assertFalse(requester.can(Permission.manageRequests))
    }

    @Test
    fun aUserIsScopedToItsServer() {
        val jellyfin = ServerRef(ServiceType.jellyfin, "server-1")
        assertNotEquals(User("1", seerr, "Same"), User("1", jellyfin, "Same"))
        assertEquals(User("1", seerr, "Same"), User("1", ServerRef(ServiceType.seerr, "server-1"), "Same"))
    }

    @Test
    fun blankIdIsRejected() {
        assertFailsWith<IllegalArgumentException> { User("", seerr, "Nobody") }
    }
}
