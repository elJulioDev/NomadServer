package com.eljuliodev.servidormc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `properties get conservative defaults when the file does not exist`() {
        val dir = temp.newFolder()
        ServerFiles.ensureProperties(dir)
        val text = File(dir, "server.properties").readText()
        assertTrue(text.contains("view-distance=6"))
        assertTrue(text.contains("simulation-distance=4"))
        assertTrue(text.contains("sync-chunk-writes=false"))
        // 26.x defaults white-list to true; players couldn't join until this was explicit.
        assertTrue(text.contains("white-list=false"))
    }

    @Test
    fun `existing properties keep their values and get the missing defaults`() {
        val dir = temp.newFolder()
        val props = File(dir, "server.properties")
        // Caso real: el server se creó desde la app (server.properties ya escrito con los ajustes
        // elegidos) y le faltan las claves de rendimiento móvil.
        props.writeText("motd=Mi server\nview-distance=10\n")
        ServerFiles.ensureProperties(dir)
        val text = props.readText()
        assertTrue(text.contains("motd=Mi server"))
        assertTrue(text.contains("view-distance=10"))
        assertTrue(text.contains("simulation-distance=4"))
        assertTrue(text.contains("sync-chunk-writes=false"))
        assertFalse(text.contains("view-distance=6"))
    }

    @Test
    fun `version comparison picks only supported releases`() {
        assertTrue(ServerFiles.isAtLeast("1.17", "1.17"))
        assertTrue(ServerFiles.isAtLeast("1.21.4", "1.17"))
        assertTrue(ServerFiles.isAtLeast("2.0", "1.17"))
        assertFalse(ServerFiles.isAtLeast("1.16.5", "1.17"))
        assertFalse(ServerFiles.isAtLeast("1.8.9", "1.17"))
        assertFalse(ServerFiles.isAtLeast("24w14a", "1.17"))
    }
}
