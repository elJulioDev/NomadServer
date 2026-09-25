package com.eljuliodev.servidormc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerSettingsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `write then read round-trips every editable option`() {
        val dir = temp.newFolder()
        val settings = ServerSettings(
            motd = "Mi server",
            maxPlayers = 12,
            gamemode = "creative",
            difficulty = "hard",
            allowFlight = true,
            whitelist = true,
            cracked = true,
            spawnProtection = 0,
        )
        ServerSettings.write(dir, settings)
        assertEquals(settings, ServerSettings.read(dir))
    }

    @Test
    fun `write keeps unknown keys and comments untouched`() {
        val dir = temp.newFolder()
        File(dir, "server.properties").writeText("# generado\nview-distance=6\nlevels=1\n")
        ServerSettings.write(dir, ServerSettings(motd = "Hola"))
        val text = File(dir, "server.properties").readText()
        assertTrue(text.contains("# generado"))
        assertTrue(text.contains("view-distance=6"))
        assertTrue(text.contains("levels=1"))
        assertTrue(text.contains("motd=Hola"))
    }

    @Test
    fun `read falls back to defaults without a file`() {
        assertEquals(ServerSettings(), ServerSettings.read(temp.newFolder()))
    }

    @Test
    fun `cracked maps to online-mode inverted`() {
        val dir = temp.newFolder()
        ServerSettings.write(dir, ServerSettings(cracked = true))
        assertTrue(File(dir, "server.properties").readText().contains("online-mode=false"))
        ServerSettings.write(dir, ServerSettings(cracked = false))
        assertTrue(File(dir, "server.properties").readText().contains("online-mode=true"))
    }
}
