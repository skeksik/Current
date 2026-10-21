package com.skeksik.current.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The last lines of the request log, oldest first, for a problem report the user copies ("Copy diagnostics"). */
fun interface RequestLogHistory {
    fun lines(): List<String>
}

/**
 * The app's [NetworkLogger]: keeps the last [capacity] lines in memory and hands each to [echo] as it arrives (logcat in an Android
 * debug build, stderr or the browser console when a flag is set; see [platformLogEcho]). A line is a [RequestLogEntry]'s text or a
 * bug's class name, so it holds no host, header, body or secret (ADR 0015). Safe to call from any thread.
 */
class RequestLogBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val echo: ((String) -> Unit)? = null,
) : NetworkLogger, RequestLogHistory {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    // Replaced as a whole, so a reader never sees a list being changed.
    private val buffer = MutableStateFlow(emptyList<String>())

    override fun lines(): List<String> = buffer.value

    override fun log(entry: RequestLogEntry) = add(entry.toString())

    override fun bug(what: String, error: Throwable) = add(bugLine(what, error))

    private fun add(line: String) {
        buffer.update { if (it.size < capacity) it + line else it.drop(it.size - capacity + 1) + line }
        echo?.invoke(line)
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}

/** [what] and the error's class name, never its message, which may quote a URL or a body (ADR 0015). */
internal fun bugLine(what: String, error: Throwable) = "bug: $what: ${error::class.simpleName}"
