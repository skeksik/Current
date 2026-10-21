package com.skeksik.current

import java.io.BufferedReader
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileLockSingleInstanceTest {
    private val folder: Path = Files.createTempDirectory("single-instance")
    private val portFile = folder.resolve("instance.port")
    private val activations = LinkedBlockingQueue<Unit>()
    private val claims = mutableListOf<Claim.Primary>()
    private var child: Process? = null

    @AfterTest
    fun cleanUp() {
        claims.forEach { it.close() }
        child?.destroyForcibly()?.waitFor()
        folder.toFile().deleteRecursively()
    }

    private fun instance(beforeSignal: (Long) -> Unit = {}) =
        FileLockSingleInstance(folder, beforeSignal, timeoutMillis = 500, retryMillis = 500)

    private fun primary(): Claim.Primary =
        assertIs<Claim.Primary>(instance().claim { activations.put(Unit) }).also(claims::add)

    private fun endpoint(): Map<String, String> =
        Files.readAllLines(portFile).map { it.split('=', limit = 2) }.associate { it[0] to it[1] }

    @Test
    fun firstClaimIsPrimaryAndWritesThePortFile() {
        primary()

        val values = endpoint()
        assertEquals("CURRENT/1", values["protocol"])
        assertTrue(values.getValue("port").toInt() in 1..65535)
        assertEquals(64, values.getValue("token").length)
        assertEquals(ProcessHandle.current().pid().toString(), values["pid"])
        if ("posix" in folder.fileSystem.supportedFileAttributeViews()) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(portFile)))
        }
    }

    @Test
    fun claimWhileHeldSignalsTheHolder() {
        primary()
        val pids = mutableListOf<Long>()

        val claim = instance(beforeSignal = pids::add).claim { error("the second claim must not be activated") }

        assertEquals(Claim.Signalled, claim)
        assertEquals(Unit, activations.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf(ProcessHandle.current().pid()), pids)
    }

    @Test
    fun closeReleasesTheLockAndDeletesThePortFile() {
        primary().close()

        assertFalse(Files.exists(portFile))
        primary()
        assertTrue(Files.exists(portFile))
    }

    @Test
    fun staleFilesFromADeadProcessDoNotBlock() {
        Files.writeString(folder.resolve("instance.lock"), "left behind")
        Files.writeString(portFile, "protocol=CURRENT/1\nport=1\ntoken=stale\npid=1\n")

        primary()

        assertNotEquals("stale", endpoint()["token"])
        assertEquals(Claim.Signalled, instance().claim {})
        assertEquals(Unit, activations.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun wrongTokenIsIgnored() {
        primary()
        val port = endpoint().getValue("port").toInt()

        for (line in listOf("CURRENT/1 ${"0".repeat(64)} activate", "CURRENT/2 x activate", "x".repeat(1000), "")) {
            Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write("$line\n".toByteArray())
                // Closed without an answer; a line cut short may reset the connection instead.
                val answer = try {
                    socket.getInputStream().read()
                } catch (e: SocketException) {
                    -1
                }
                assertEquals(-1, answer, "no answer to: ${line.take(20)}")
            }
        }

        assertNull(activations.poll(200, TimeUnit.MILLISECONDS))
        // Still listening for the right token.
        assertEquals(Claim.Signalled, instance().claim {})
        assertEquals(Unit, activations.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun heldLockWithoutAnAnswerIsNotReached() {
        FileChannel.open(folder.resolve("instance.lock"), CREATE, WRITE).use {
            it.lock()
            Files.writeString(portFile, "protocol=CURRENT/1\nport=1\ntoken=nobody\npid=1\n")

            assertEquals(Claim.NotReached, instance().claim {})
        }
    }

    @Test
    fun anotherProcessHoldsTheLockUntilItDies() {
        val java = ProcessHandle.current().info().command().orElse("java")
        val process = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "com.skeksik.current.LockHolderKt", folder.toString())
            .redirectErrorStream(true)
            .start()
            .also { child = it }
        val output = process.inputStream.bufferedReader()
        assertEquals("primary", output.nextLine())

        assertEquals(Claim.Signalled, instance().claim {})
        assertEquals("activated", output.nextLine())

        process.destroyForcibly().waitFor()
        // The killed process never deleted its port file; its lock went with it.
        assertTrue(Files.exists(portFile))
        primary()
    }

    /** The next line that is not a JVM notice (such as "Picked up JAVA_TOOL_OPTIONS"). */
    private fun BufferedReader.nextLine(): String? =
        generateSequence { readLine() }.firstOrNull { !it.startsWith("Picked up ") }
}
