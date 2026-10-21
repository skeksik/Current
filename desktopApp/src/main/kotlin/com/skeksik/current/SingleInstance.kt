package com.skeksik.current

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** One running app per user on this machine (docs/architecture.md, "Single instance (desktop)"). */
internal interface SingleInstance {
    /** Claims the app for this process. [onActivate] runs on a background thread each time a later launch asks this one to come forward. */
    fun claim(onActivate: () -> Unit): Claim
}

internal sealed interface Claim {
    /** This process is the app. [close] releases the claim; it is safe to call more than once. */
    class Primary(private val release: () -> Unit) : Claim, AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    /** Another process is the app and was asked to come forward: this one exits. */
    data object Signalled : Claim

    /** Another process holds the claim but did not answer in time: this one still exits, two apps would share the same files. */
    data object NotReached : Claim
}

/**
 * The claim is an exclusive lock on `instance.lock` in [folder], which the OS drops when the process ends, however it ends, so a crash
 * leaves nothing that blocks the next start. The holder listens on a loopback port and writes it to `instance.port` with a random token
 * (owner-only on POSIX). A later launch sends `CURRENT/1 <token> activate` there and the holder answers `OK`. The version and the command
 * word leave room for more commands; activate is the only one.
 *
 * [beforeSignal] gets the holder's process id just before a later launch signals it (Windows lets that process come to the front).
 */
internal class FileLockSingleInstance(
    private val folder: Path,
    private val beforeSignal: (pid: Long) -> Unit = {},
    private val timeoutMillis: Int = 1_000,
    private val retryMillis: Long = 2_000,
) : SingleInstance {
    private val lockFile = folder.resolve("instance.lock")
    private val portFile = folder.resolve("instance.port")

    override fun claim(onActivate: () -> Unit): Claim {
        Files.createDirectories(folder)
        val channel = FileChannel.open(lockFile, CREATE, WRITE)
        val lock = try {
            channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            // Held by this JVM already (only in tests): the same answer as another process holding it.
            null
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        if (lock == null) {
            channel.close()
            return signal()
        }
        return serve(channel, lock, onActivate)
    }

    private fun serve(channel: FileChannel, lock: FileLock, onActivate: () -> Unit): Claim.Primary {
        val token = newToken()
        val server = try {
            ServerSocket(0, BACKLOG, LOOPBACK).also { server ->
                try {
                    writePortFile(server.localPort, token)
                } catch (e: IOException) {
                    server.close()
                    throw e
                }
            }
        } catch (e: IOException) {
            // Still the only app: later launches find no port and exit without bringing this one forward.
            System.err.println("Current: later launches cannot bring this window forward (${e.javaClass.simpleName})")
            null
        }
        if (server != null) {
            Thread({ accept(server, token, onActivate) }, "single-instance").apply { isDaemon = true }.start()
        }
        // Runs in a shutdown hook: every step runs even when one before it fails, and nothing is thrown out of it.
        return Claim.Primary {
            quietly { server?.close() }
            // Before the lock goes, so a process that claims next never loses its own port file to this one. If it stays, the next
            // holder overwrites it.
            if (server != null) quietly { Files.deleteIfExists(portFile) }
            quietly { lock.release() }
            quietly { channel.close() }
        }
    }

    private fun accept(server: ServerSocket, token: String, onActivate: () -> Unit) {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                return // closed by Primary.close
            } catch (e: IOException) {
                continue
            }
            val activate = try {
                socket.use { handle(it, token) }
            } catch (e: IOException) {
                false
            }
            if (activate) onActivate()
        }
    }

    /** True for a well-formed activate with the right token. Anything else is closed without an answer. */
    private fun handle(socket: Socket, token: String): Boolean {
        socket.soTimeout = timeoutMillis
        val words = socket.getInputStream().readLine()?.split(' ') ?: return false
        if (words.size != 3 || words[0] != PROTOCOL || !sameToken(words[1], token) || words[2] != ACTIVATE) return false
        socket.getOutputStream().apply {
            write("$OK\n".toByteArray(Charsets.US_ASCII))
            flush()
        }
        return true
    }

    private fun signal(): Claim {
        val deadline = System.nanoTime() + retryMillis * 1_000_000
        while (true) {
            // Re-read each time: the holder may be starting and not have written its port yet.
            val endpoint = readEndpoint()
            if (endpoint != null && send(endpoint)) return Claim.Signalled
            if (System.nanoTime() >= deadline) return Claim.NotReached
            Thread.sleep(RETRY_PAUSE_MILLIS)
        }
    }

    private fun send(endpoint: Endpoint): Boolean {
        beforeSignal(endpoint.pid)
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK, endpoint.port), timeoutMillis)
                socket.soTimeout = timeoutMillis
                socket.getOutputStream().apply {
                    write("$PROTOCOL ${endpoint.token} $ACTIVATE\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                socket.getInputStream().readLine() == OK
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun writePortFile(port: Int, token: String) {
        val posix = "posix" in folder.fileSystem.supportedFileAttributeViews()
        val temp = if (posix) {
            Files.createTempFile(folder, "instance", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createTempFile(folder, "instance", ".tmp")
        }
        try {
            val pid = ProcessHandle.current().pid()
            Files.writeString(temp, "protocol=$PROTOCOL\nport=$port\ntoken=$token\npid=$pid\n")
            // A reader sees no file or a whole one, never half of it. Whether an atomic move replaces an existing file is implementation
            // specific (Files.move), so a stale one is deleted first if it does not; only the lock holder writes this file.
            try {
                Files.move(temp, portFile, ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.deleteIfExists(portFile)
                Files.move(temp, portFile, ATOMIC_MOVE)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun readEndpoint(): Endpoint? {
        val values = try {
            Files.readAllLines(portFile).mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }.associate { it[0] to it[1] }
        } catch (e: IOException) {
            return null
        }
        if (values["protocol"] != PROTOCOL) return null
        val port = values["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val token = values["token"]?.takeIf { it.isNotEmpty() && ' ' !in it } ?: return null
        val pid = values["pid"]?.toLongOrNull() ?: return null
        return Endpoint(port, token, pid)
    }

    private class Endpoint(val port: Int, val token: String, val pid: Long)

    private companion object {
        const val PROTOCOL = "CURRENT/1"
        const val ACTIVATE = "activate"
        const val OK = "OK"
        const val BACKLOG = 8
        const val MAX_LINE = 256
        const val RETRY_PAUSE_MILLIS = 100L

        /** 127.0.0.1 itself, never another interface; built from its bytes, so nothing is looked up. */
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        inline fun quietly(step: () -> Unit) {
            try {
                step()
            } catch (e: IOException) {
                // Nothing left to do about it on the way out.
            }
        }

        fun newToken(): String {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun sameToken(given: String, expected: String): Boolean =
            MessageDigest.isEqual(given.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))

        /** One ASCII line of at most [MAX_LINE] bytes, without its newline; null when the peer sent none. */
        fun InputStream.readLine(): String? {
            val line = StringBuilder()
            while (line.length < MAX_LINE) {
                val byte = read()
                if (byte == -1) return null
                if (byte == '\n'.code) return line.toString()
                line.append(byte.toChar())
            }
            return null
        }
    }
}
