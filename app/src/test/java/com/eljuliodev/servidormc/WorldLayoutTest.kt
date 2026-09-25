package com.eljuliodev.servidormc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorldLayoutTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun region(dir: File) {
        File(dir, "region").mkdirs()
        File(File(dir, "region"), "r.0.0.mca").writeText("x")
    }

    @Test
    fun `classic layout - overworld in the level and nether as a sibling`() {
        val root = temp.newFolder()
        File(root, "world").mkdirs()
        File(root, "world/level.dat").writeText("x")
        region(File(root, "world"))
        region(File(root, "world_nether"))

        val labels = WorldLayout.dimensions(root).map { it.label }.sorted()
        assertEquals(listOf("Mundo", "Nether"), labels)
    }

    @Test
    fun `26_1 layout - dimensions under dimensions minecraft`() {
        val root = temp.newFolder()
        val level = File(root, "world").apply { mkdirs() }
        val minecraft = File(File(level, "dimensions"), "minecraft")
        File(level, "level.dat").writeText("x")
        region(File(minecraft, "overworld"))
        region(File(minecraft, "the_nether"))
        region(File(minecraft, "the_end"))

        val labels = WorldLayout.dimensions(root).map { it.label }.sorted()
        assertEquals(listOf("End", "Mundo", "Nether"), labels)
    }

    @Test
    fun `region dirs are found at any depth and only when they have mca files`() {
        val root = temp.newFolder()
        File(root, "world/region").mkdirs() // vacía: no cuenta
        val level = File(root, "world")
        File(level, "level.dat").writeText("x")

        val empty = WorldLayout.regionDirs(root)
        assertTrue(empty.isEmpty())

        File(File(root, "world/dimensions/minecraft/overworld/region"), "r.0.0.mca").apply {
            parentFile?.mkdirs()
            writeText("x")
        }
        assertEquals(1, WorldLayout.regionDirs(root).size)
    }

    @Test
    fun `regenerate deletes the dimension folder in any layout`() {
        val root = temp.newFolder()
        File(root, "world").mkdirs()
        File(root, "world/level.dat").writeText("x")
        region(File(root, "world"))
        val oldNether = File(root, "world_nether").apply { mkdirs() }
        val newNether = File(root, "world/dimensions/minecraft/the_nether").apply { mkdirs() }

        assertTrue(WorldTools.regenerate(root, WorldLayout.NETHER))
        assertTrue(!oldNether.exists())
        assertTrue(!newNether.exists())
        assertTrue(File(root, "world").exists())
    }
}
