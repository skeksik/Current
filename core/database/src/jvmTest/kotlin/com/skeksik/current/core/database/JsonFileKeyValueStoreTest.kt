package com.skeksik.current.core.database

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileKeyValueStoreTest {
    private val folder: Path = Files.createTempDirectory("current-settings-test")
    private val file: Path = folder.resolve("nested").resolve("settings.json")

    @AfterTest
    fun deleteFolder() {
        folder.toFile().deleteRecursively()
    }

    @Test
    fun valuesSurviveANewStore() {
        JsonFileKeyValueStore(file).apply {
            put("servers", """["a"]""")
            put("theme", "dark")
            remove("theme")
        }
        val reopened = JsonFileKeyValueStore(file)
        assertEquals("""["a"]""", reopened.get("servers"))
        assertNull(reopened.get("theme"))
    }

    @Test
    fun startsEmptyWithoutAFile() {
        assertNull(JsonFileKeyValueStore(file).get("servers"))
    }

    @Test
    fun writesLeaveOnlyTheSettingsFile() {
        JsonFileKeyValueStore(file).apply { repeat(3) { put("count", "$it") } }
        assertEquals(listOf("settings.json"), file.parent.listDirectoryEntries().map { it.name })
        assertTrue(""""count": "2"""" in file.readText())
    }

    @Test
    fun anUnreadableFileIsSetAsideAndTheStoreStartsEmpty() {
        Files.createDirectories(file.parent)
        file.writeText("{ not json")
        val store = JsonFileKeyValueStore(file)
        assertNull(store.get("servers"))
        assertEquals("{ not json", file.resolveSibling("settings.json.unreadable").readText())
        store.put("servers", "[]")
        assertEquals("[]", JsonFileKeyValueStore(file).get("servers"))
    }

    @Test
    fun aFileThatIsNotUtf8IsSetAsideAndTheStoreStartsEmpty() {
        Files.createDirectories(file.parent)
        // Saved as UTF-16 by a text editor: the byte order mark is not valid UTF-8.
        val bytes = """{"servers":"[]"}""".toByteArray(Charsets.UTF_16)
        Files.write(file, bytes)
        val store = JsonFileKeyValueStore(file)
        assertNull(store.get("servers"))
        assertTrue(Files.readAllBytes(file.resolveSibling("settings.json.unreadable")).contentEquals(bytes))
        store.put("theme", "dark")
        assertEquals("dark", JsonFileKeyValueStore(file).get("theme"))
    }
}
