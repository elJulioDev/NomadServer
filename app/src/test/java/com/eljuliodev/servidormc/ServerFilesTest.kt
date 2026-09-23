package com.eljuliodev.servidormc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `eula is created once and never overwritten`() {
        val dir = temp.newFolder()
        ServerFiles.ensureEula(dir)
        val eula = File(dir, "eula.txt")
        assertEquals("eula=true\n", eula.readText())

        eula.writeText("eula=false\n")
        ServerFiles.ensureEula(dir)
        assertEquals("eula=false\n", eula.readText())
    }

    @Test
    fun `properties get conservative defaults once`() {
        val dir = temp.newFolder()
        ServerFiles.ensureProperties(dir)
        val props = File(dir, "server.properties")
        val text = props.readText()
        assertTrue(text.contains("view-distance=6"))
        assertTrue(text.contains("simulation-distance=4"))
        assertTrue(text.contains("sync-chunk-writes=false"))
        // 26.x defaults white-list to true; players couldn't join until this was explicit.
        assertTrue(text.contains("white-list=false"))

        props.writeText("view-distance=10\n")
        ServerFiles.ensureProperties(dir)
        assertEquals("view-distance=10\n", props.readText())
    }
}
