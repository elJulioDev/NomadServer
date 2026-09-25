package com.eljuliodev.servidormc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorldToolsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `level name comes from server properties`() {
        val dir = temp.newFolder()
        File(dir, "server.properties").writeText("motd=x\nlevel-name=mundo\n")
        assertEquals("mundo", WorldTools.levelName(dir))
        File(dir, "server.properties").writeText("motd=x\n")
        assertEquals("world", WorldTools.levelName(dir))
    }

    @Test
    fun `regenerating nether removes its folders but not the overworld`() {
        val dir = temp.newFolder()
        File(dir, "world").mkdirs()
        File(dir, "world/level.dat").writeText("x")
        File(dir, "world/DIM-1").mkdirs()
        File(dir, "world_nether").mkdirs()

        assertTrue(WorldTools.regenerate(dir, WorldTools.NETHER))
        assertFalse(File(dir, "world_nether").exists())
        assertFalse(File(dir, "world/DIM-1").exists())
        assertTrue(File(dir, "world/level.dat").exists())
    }

    @Test
    fun `importing a zipped world replaces the world folder`() {
        val dir = temp.newFolder()
        File(dir, "world").mkdirs()
        File(dir, "world/level.dat").writeText("viejo")

        val bytes = zip("mundo/level.dat" to "nuevo", "mundo/region/r.0.0.mca" to "data")
        WorldTools.importZip(dir, ByteArrayInputStream(bytes)).getOrThrow()

        assertEquals("nuevo", File(dir, "world/level.dat").readText())
        assertTrue(File(dir, "world/region/r.0.0.mca").exists())
        assertFalse(File(dir, "world-import").exists())
    }

    @Test
    fun `importing rejects a zip that escapes the destination`() {
        val dir = temp.newFolder()
        val bytes = zip("level.dat" to "x", "../evil.txt" to "boom")
        assertTrue(WorldTools.importZip(dir, ByteArrayInputStream(bytes)).isFailure)
        assertFalse(File(dir, "evil.txt").exists())
    }

    @Test
    fun `importing rejects a zip without a world`() {
        val dir = temp.newFolder()
        assertTrue(WorldTools.importZip(dir, ByteArrayInputStream(zip("readme.txt" to "hola"))).isFailure)
    }

    @Test
    fun `sizes counts each part of the server`() {
        val dir = temp.newFolder()
        File(dir, "world").mkdirs()
        File(dir, "world/level.dat").writeText("12345")
        File(dir, "world_nether").mkdirs()
        File(dir, "world_nether/nether.dat").writeText("123")

        val sizes = WorldTools.sizes(dir, freeBytes = 1000)
        assertEquals(5L, sizes.world)
        assertEquals(3L, sizes.nether)
        assertEquals(0L, sizes.end)
        assertEquals(1000L, sizes.free)
    }
}
