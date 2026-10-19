package com.skeksik.current.core.database

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.koin.core.scope.Scope

internal actual fun Scope.platformKeyValueStore(): KeyValueStore = JsonFileKeyValueStore(AppFolders.config().resolve("settings.json"))

/**
 * Settings as one JSON object in [file], kept in memory and written whole on every change: to a temporary file next to it, then moved
 * over it atomically, so a crash leaves the old or the new file, never half of one. A file that does not parse is kept as
 * `<name>.unreadable` and the store starts empty, so the app still starts.
 */
class JsonFileKeyValueStore(private val file: Path) : KeyValueStore {
    private val values: MutableMap<String, String> = load()

    @Synchronized
    override fun get(key: String): String? = values[key]

    @Synchronized
    override fun put(key: String, value: String) {
        if (values[key] == value) return
        values[key] = value
        save()
    }

    @Synchronized
    override fun remove(key: String) {
        if (values.remove(key) != null) save()
    }

    private fun load(): MutableMap<String, String> {
        if (!Files.exists(file)) return mutableMapOf()
        val text = Files.readString(file)
        return try {
            json.decodeFromString(serializer, text).toMutableMap()
        } catch (e: SerializationException) {
            setAside()
        } catch (e: IllegalArgumentException) {
            setAside()
        }
    }

    private fun setAside(): MutableMap<String, String> {
        Files.move(file, file.resolveSibling(file.fileName.toString() + ".unreadable"), REPLACE_EXISTING)
        return mutableMapOf()
    }

    private fun save() {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(serializer, values.toSortedMap()))
            try {
                Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            Files.deleteIfExists(temporary)
            throw e
        }
    }

    private companion object {
        val json = Json { prettyPrint = true }
        val serializer = MapSerializer(String.serializer(), String.serializer())
    }
}

/** Where the desktop app keeps its files, by the platform's conventions. */
object AppFolders {
    /**
     * Settings: `%APPDATA%\Current` on Windows, `$XDG_CONFIG_HOME/current` (default `~/.config/current`) on Linux, and
     * `~/Library/Application Support/Current` when the desktop app runs on a Mac during development.
     */
    fun config(): Path {
        val home = Path.of(System.getProperty("user.home"))
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.startsWith("windows") ->
                (System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home.resolve("AppData").resolve("Roaming"))
                    .resolve("Current")
            os.startsWith("mac") -> home.resolve("Library").resolve("Application Support").resolve("Current")
            else ->
                (System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() && Path.of(it).isAbsolute }?.let(Path::of)
                    ?: home.resolve(".config"))
                    .resolve("current")
        }
    }
}
