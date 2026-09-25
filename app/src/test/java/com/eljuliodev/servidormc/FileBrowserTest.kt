package com.eljuliodev.servidormc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileBrowserTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `lists directories first and hides the import temp folder`() {
        val root = temp.newFolder()
        File(root, "world").mkdirs()
        File(root, "server.jar").writeText("x")
        File(root, "world-import").mkdirs()

        val entries = FileBrowser.list(root, "")
        assertEquals(listOf("world", "server.jar"), entries.map { it.name })
        assertTrue(entries.first().directory)
        assertEquals(1L, entries.first { it.name == "server.jar" }.size)
    }

    @Test
    fun `cannot escape the server directory`() {
        val root = temp.newFolder()
        File(root, "world").mkdirs()
        assertNull(FileBrowser.resolve(root, "../otro"))
        assertNull(FileBrowser.resolve(root, "world/../../fuera"))
        assertNotNull(FileBrowser.resolve(root, "world"))
        assertEquals(root.canonicalPath, FileBrowser.resolve(root, "")?.canonicalPath)
    }
}
