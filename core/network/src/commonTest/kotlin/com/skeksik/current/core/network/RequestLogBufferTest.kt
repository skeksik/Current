package com.skeksik.current.core.network

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestLogBufferTest {
    private val server = ServerRef(ServiceType.jellyfin, "server-1")

    private fun entry(n: Int) = RequestLogEntry(server, "GET", "/Items/$n", 200, 5)

    @Test
    fun keepsTheLastEntriesOldestFirst() {
        val buffer = RequestLogBuffer(capacity = 3)
        (1..5).forEach { buffer.log(entry(it)) }
        assertEquals(listOf(3, 4, 5).map { entry(it).toString() }, buffer.lines())
    }

    @Test
    fun startsEmptyAndFillsUpToItsCapacity() {
        val buffer = RequestLogBuffer(capacity = 3)
        assertEquals(emptyList(), buffer.lines())
        buffer.log(entry(1))
        buffer.log(entry(2))
        assertEquals(listOf(entry(1).toString(), entry(2).toString()), buffer.lines())
    }

    @Test
    fun theDefaultCapacityIsTwoHundred() {
        val buffer = RequestLogBuffer()
        repeat(250) { buffer.log(entry(it)) }
        assertEquals(200, buffer.lines().size)
        assertEquals(entry(50).toString(), buffer.lines().first())
        assertEquals(entry(249).toString(), buffer.lines().last())
    }

    @Test
    fun aCopyDoesNotChangeWithLaterEntries() {
        val buffer = RequestLogBuffer(capacity = 2)
        buffer.log(entry(1))
        val copy = buffer.lines()
        buffer.log(entry(2))
        buffer.log(entry(3))
        assertEquals(listOf(entry(1).toString()), copy)
    }

    @Test
    fun bugsKeepTheClassNameOnly() {
        val echoed = mutableListOf<String>()
        val buffer = RequestLogBuffer(echo = { echoed += it })
        buffer.bug("Network changes failed", IllegalStateException("https://media.example.com/Items?api_key=secret"))
        assertEquals(listOf("bug: Network changes failed: IllegalStateException"), buffer.lines())
        assertEquals(buffer.lines(), echoed)
    }

    @Test
    fun everyLineIsEchoedAsItArrives() {
        val echoed = mutableListOf<String>()
        val buffer = RequestLogBuffer(capacity = 1, echo = { echoed += it })
        buffer.log(entry(1))
        buffer.log(entry(2))
        assertEquals(listOf(entry(1).toString(), entry(2).toString()), echoed)
        assertEquals(listOf(entry(2).toString()), buffer.lines())
    }

    @Test
    fun capacityMustBePositive() {
        assertFailsWith<IllegalArgumentException> { RequestLogBuffer(capacity = 0) }
    }
}
