package com.skeksik.current.core.database

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Settings as one JSON object in [file], kept in memory and written whole on every change: to a temporary file next to it, then moved
 * over it atomically, so a crash leaves the old or the new file, never half of one. A file that does not parse is kept as
 * `<name>.unreadable` and the store starts empty, so the app still starts.
 *
 * With [ownerOnly], the folder and file are readable by this user only where the file system has POSIX permissions (Linux, macOS);
 * used for secrets kept in a file (ADR 0017).
 */
class JsonFileKeyValueStore(private val file: Path, private val ownerOnly: Boolean = false) : KeyValueStore {
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

    /** Every key, for moving values to another store. */
    @Synchronized
    fun keys(): Set<String> = values.keys.toSet()

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
        createFolder()
        val temporary = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            if (ownerOnly && posix) Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
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

    private fun createFolder() {
        val folder = file.parent
        if (Files.isDirectory(folder)) return
        Files.createDirectories(folder)
        if (ownerOnly && posix) Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"))
    }

    private val posix: Boolean get() = "posix" in file.fileSystem.supportedFileAttributeViews()

    private companion object {
        val json = Json { prettyPrint = true }
        val serializer = MapSerializer(String.serializer(), String.serializer())
    }
}
