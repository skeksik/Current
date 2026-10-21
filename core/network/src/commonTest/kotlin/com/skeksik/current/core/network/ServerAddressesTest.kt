package com.skeksik.current.core.network

import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServerAddressesTest {
    private val ip = ServerAddress("http://192.168.1.10:8096", AddressScope.Local)
    private val lan = ServerAddress("http://media.lan:8096", AddressScope.Local)
    private val internet = ServerAddress("https://media.example.com", AddressScope.Internet)

    private fun server(vararg addresses: ServerAddress) =
        SavedServer(ServerRef(ServiceType.jellyfin, "a"), "0123456789abcdef0123456789abcdef", "Home", addresses.toList())

    @Test
    fun anAddedAddressComesLast() {
        assertEquals(listOf(ip, internet), server(ip).withAddress(internet).addresses)
    }

    @Test
    fun anAddressIsNotAddedTwice() {
        assertFailsWith<IllegalArgumentException> { server(ip).withAddress(ip) }
    }

    @Test
    fun removingKeepsTheOrderOfTheRest() {
        assertEquals(listOf(ip, internet), server(ip, lan, internet).withoutAddress(lan.url).addresses)
    }

    @Test
    fun theLastAddressCannotBeRemoved() {
        assertFalse(server(ip).canRemoveAddress(ip.url))
        assertFailsWith<IllegalArgumentException> { server(ip).withoutAddress(ip.url) }
    }

    @Test
    fun anAddressThatIsNotSavedCannotBeRemoved() {
        assertFalse(server(ip, lan).canRemoveAddress(internet.url))
        assertTrue(server(ip, lan).canRemoveAddress(lan.url))
    }

    @Test
    fun movingShiftsTheAddressesBetween() {
        assertEquals(listOf(internet, ip, lan), server(ip, lan, internet).withAddressMoved(2, 0).addresses)
        assertEquals(listOf(lan, internet, ip), server(ip, lan, internet).withAddressMoved(0, 2).addresses)
    }

    @Test
    fun movingInPlaceChangesNothing() {
        val saved = server(ip, lan)
        assertSame(saved, saved.withAddressMoved(1, 1))
    }

    @Test
    fun movingOutsideTheListFails() {
        assertFailsWith<IllegalArgumentException> { server(ip, lan).withAddressMoved(0, 2) }
    }
}
