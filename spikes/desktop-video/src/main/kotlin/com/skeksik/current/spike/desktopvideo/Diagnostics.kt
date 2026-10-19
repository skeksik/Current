package com.skeksik.current.spike.desktopvideo

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.DefaultWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Crash and event trail for the spike: Java exceptions go to `crash-<time>.txt`, and [event] lines go to `events-<time>.log`,
 * flushed per line so they survive a native crash (whose dump the JVM writes to `hs_err_pid<pid>.log` in the same folder).
 */
object Diagnostics {
    private val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val eventsFile = File(SpikePaths.outDir, "events-$stamp.log")
    private val events = PrintWriter(eventsFile.bufferedWriter(), true)

    fun event(message: String) {
        val line = "${LocalDateTime.now()} [${Thread.currentThread().name}] ${redact(message)}"
        synchronized(events) { events.println(line) }
        println(line)
    }

    fun crash(where: String, error: Throwable) {
        val file = File(SpikePaths.outDir, "crash-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))}.txt")
        file.printWriter().use { out ->
            out.println("Uncaught exception in $where")
            out.println(redact(error.stackTraceToString()))
        }
        event("CRASH in $where: $error (stack trace in ${file.name})")
        System.err.println(redact(error.stackTraceToString()))
    }

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> crash("thread ${thread.name}", error) }
        event(
            "start: java ${System.getProperty("java.version")} (${System.getProperty("java.vendor")}), " +
                "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}, " +
                "skiko.renderApi=${System.getProperty("skiko.renderApi") ?: "default"}, out=${SpikePaths.outDir.absolutePath}",
        )
    }

    /** Set by the self-test: an exception then ends the process (exit code 3) instead of waiting on an error dialog. */
    @Volatile var exitOnWindowException = false

    /** Logs exceptions thrown inside a window's composition, then keeps Compose's default behaviour (error dialog, close the window). */
    @OptIn(ExperimentalComposeUiApi::class)
    val windowExceptionHandlerFactory = WindowExceptionHandlerFactory { window ->
        WindowExceptionHandler { error ->
            crash("window '${(window as? java.awt.Frame)?.title ?: (window as? java.awt.Dialog)?.title}'", error)
            if (exitOnWindowException) exitProcess(3)
            DefaultWindowExceptionHandlerFactory.exceptionHandler(window).onException(error)
        }
    }
}
