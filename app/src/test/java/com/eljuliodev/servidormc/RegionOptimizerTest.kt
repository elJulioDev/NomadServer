package com.eljuliodev.servidormc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream

class RegionOptimizerTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Compuesto NBT mínimo con `InhabitedTime`. */
    private fun chunkNbt(inhabited: Long): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeByte(10)
            data.writeUTF("")
            data.writeByte(3)
            data.writeUTF("DataVersion")
            data.writeInt(3465)
            data.writeByte(4)
            data.writeUTF("InhabitedTime")
            data.writeLong(inhabited)
            data.writeByte(0)
        }
        return out.toByteArray()
    }

    /** `.mca` sintético: header de 2 sectores + chunks zlib alineados a 4096. */
    private fun region(chunks: Map<Int, ByteArray>): ByteArray {
        val header = ByteArray(8192)
        val entries = ArrayList<ByteArray>()
        var sector = 2
        chunks.forEach { (index, nbt) ->
            val compressed = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { z -> z.write(nbt) } }.toByteArray()
            val byteLength = 1 + compressed.size
            val sectors = (4 + byteLength + 4095) / 4096
            val location = index * 4
            header[location] = ((sector shr 16) and 0xFF).toByte()
            header[location + 1] = ((sector shr 8) and 0xFF).toByte()
            header[location + 2] = (sector and 0xFF).toByte()
            header[location + 3] = sectors.toByte()
            val entry = ByteArray(sectors * 4096)
            entry[0] = ((byteLength ushr 24) and 0xFF).toByte()
            entry[1] = ((byteLength ushr 16) and 0xFF).toByte()
            entry[2] = ((byteLength ushr 8) and 0xFF).toByte()
            entry[3] = (byteLength and 0xFF).toByte()
            entry[4] = 2 // zlib
            System.arraycopy(compressed, 0, entry, 5, compressed.size)
            entries.add(entry)
            sector += sectors
        }
        val out = ByteArrayOutputStream()
        out.write(header)
        entries.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun world(dir: File): File {
        val world = File(dir, "world")
        File(world, "region").mkdirs()
        return world
    }

    @Test
    fun `preview counts unvisited chunks and optimize removes only those`() {
        val dir = temp.newFolder()
        val region = File(world(dir), "region/r.0.0.mca")
        region.writeBytes(region(mapOf(0 to chunkNbt(0), 1 to chunkNbt(500))))
        val before = region.length()

        val preview = RegionOptimizer.preview(dir, listOf("world"))
        assertEquals(2, preview.chunks)
        assertEquals(1, preview.unvisited)
        assertTrue(preview.reclaimable > 0)
        // La vista previa no toca el archivo.
        assertEquals(before, region.length())

        RegionOptimizer.optimize(dir, listOf("world"))
        assertTrue(region.length() < before)

        val after = RegionOptimizer.preview(dir, listOf("world"))
        assertEquals(1, after.chunks)
        assertEquals(0, after.unvisited)
    }

    @Test
    fun `chunks with a missing InhabitedTime are kept`() {
        val dir = temp.newFolder()
        val region = File(world(dir), "region/r.0.0.mca")
        // NBT sin InhabitedTime (solo DataVersion).
        val nbt = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeByte(10)
                data.writeUTF("")
                data.writeByte(3)
                data.writeUTF("DataVersion")
                data.writeInt(3465)
                data.writeByte(0)
            }
        }.toByteArray()
        region.writeBytes(region(mapOf(0 to nbt)))

        val preview = RegionOptimizer.preview(dir, listOf("world"))
        assertEquals(1, preview.chunks)
        assertEquals(0, preview.unvisited)
    }

    @Test
    fun `old compressed logs are removed`() {
        val dir = temp.newFolder()
        val logs = File(dir, "logs").apply { mkdirs() }
        val old = File(logs, "2020-01-01-1.log.gz").apply {
            writeText("x".repeat(100))
            setLastModified(1)
        }
        val fresh = File(logs, "latest.log").apply { writeText("y".repeat(50)) }

        val preview = RegionOptimizer.preview(dir, listOf("world"))
        assertEquals(1, preview.logFiles)
        assertTrue(preview.logBytes > 0)

        RegionOptimizer.optimize(dir, listOf("world"))
        assertTrue(!old.exists())
        assertTrue(fresh.exists())
    }
}
