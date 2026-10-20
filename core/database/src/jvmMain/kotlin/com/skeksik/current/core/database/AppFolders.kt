package com.skeksik.current.core.database

import java.nio.file.Path

/** Where the desktop app keeps its files, by the platform's conventions (ADR 0016, ADR 0017). */
object AppFolders {
    private val home: Path get() = Path.of(System.getProperty("user.home"))
    private val os: String get() = System.getProperty("os.name").lowercase()

    internal val isWindows: Boolean get() = os.startsWith("windows")
    private val isMac: Boolean get() = os.startsWith("mac")

    /**
     * Settings, which may roam with the user: `%APPDATA%\Current` on Windows, `$XDG_CONFIG_HOME/current` (default `~/.config/current`)
     * on Linux, and `~/Library/Application Support/Current` when the desktop app runs on a Mac during development.
     */
    fun config(): Path = when {
        isWindows -> windowsFolder("APPDATA", "Roaming")
        isMac -> macFolder()
        else -> xdgFolder("XDG_CONFIG_HOME", ".config")
    }

    /**
     * Values that belong to this machine and must never roam, such as the device id and secrets kept in a file: `%LOCALAPPDATA%\Current`
     * on Windows, `$XDG_STATE_HOME/current` (default `~/.local/state/current`) on Linux.
     */
    fun local(): Path = when {
        isWindows -> windowsFolder("LOCALAPPDATA", "Local")
        isMac -> macFolder()
        else -> xdgFolder("XDG_STATE_HOME", ".local/state")
    }

    private fun windowsFolder(variable: String, appData: String): Path =
        (env(variable) ?: home.resolve("AppData").resolve(appData)).resolve("Current")

    private fun macFolder(): Path = home.resolve("Library").resolve("Application Support").resolve("Current")

    /** The XDG Base Directory specification: a relative path in the variable is invalid and ignored. */
    private fun xdgFolder(variable: String, default: String): Path =
        (env(variable)?.takeIf { it.isAbsolute } ?: home.resolve(default)).resolve("current")

    private fun env(variable: String): Path? = System.getenv(variable)?.takeIf { it.isNotBlank() }?.let(Path::of)
}
