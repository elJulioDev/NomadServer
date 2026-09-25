package com.eljuliodev.servidormc

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Optimiza el espacio de los mundos:
 *  - quita de los archivos de región (`.mca`) los chunks **nunca visitados**
 *    (`InhabitedTime == 0`), que Minecraft regenera idénticos desde la semilla al volver a
 *    cargarlos;
 *  - borra logs comprimidos viejos.
 *
 * Sólo debe correr con el servidor **apagado**. Cualquier chunk con `InhabitedTime > 0` (o
 * sin ese campo) se conserva. Los cambios hechos a mano en un chunk sin visitas (comandos,
 * editores externos) se perderían: la UI lo advierte antes de aplicar.
 */
object RegionOptimizer {

    private const val SECTOR_SIZE = 4096
    private const val HEADER_BYTES = SECTOR_SIZE * 2
    private const val CHUNKS_PER_REGION = 1024
    private const val LOG_KEEP_MS = 7L * 24 * 60 * 60 * 1000

    data class Preview(
        val chunks: Int,
        val unvisited: Int,
        val regionBefore: Long,
        val regionAfter: Long,
        val logFiles: Int,
        val logBytes: Long,
    ) {
        val reclaimable: Long get() = (regionBefore - regionAfter).coerceAtLeast(0L) + logBytes
    }

    /** Cuenta sin tocar nada (para la vista previa). */
    fun preview(dir: File, worlds: List<String>): Preview = run(dir, worlds, apply = false)

    /** Aplica la optimización y devuelve lo que se liberó. */
    fun optimize(dir: File, worlds: List<String>): Preview = run(dir, worlds, apply = true)

    private fun run(dir: File, worlds: List<String>, apply: Boolean): Preview {
        var chunks = 0
        var unvisited = 0
        var before = 0L
        var after = 0L
        worlds.forEach { world ->
            val worldDir = File(dir, world)
            File(worldDir, "region").listFiles { file -> file.name.endsWith(".mca") }?.forEach { region ->
                val result = optimizeRegion(region, worldDir, apply)
                chunks += result.kept + result.removed
                unvisited += result.removed
                before += result.bytesBefore
                after += result.bytesAfter
            }
        }
        val (logFiles, logBytes) = cleanLogs(dir, apply)
        return Preview(chunks, unvisited, before, after, logFiles, logBytes)
    }

    private data class Chunk(val index: Int, val compression: Int, val payload: ByteArray, val timestamp: Int)

    private data class RegionResult(
        val kept: Int,
        val removed: Int,
        val bytesBefore: Long,
        val bytesAfter: Long,
    )

    private fun optimizeRegion(file: File, worldDir: File, apply: Boolean): RegionResult {
        val bytes = file.readBytes()
        if (bytes.size < HEADER_BYTES) return RegionResult(0, 0, bytes.size.toLong(), bytes.size.toLong())

        val kept = ArrayList<Chunk>()
        val removedIndices = HashSet<Int>()
        for (index in 0 until CHUNKS_PER_REGION) {
            val chunk = readChunk(bytes, index) ?: continue
            if (isUnvisited(chunk)) removedIndices.add(index) else kept.add(chunk)
        }
        if (removedIndices.isEmpty()) {
            return RegionResult(kept.size, 0, bytes.size.toLong(), bytes.size.toLong())
        }

        val after = HEADER_BYTES + kept.sumOf { paddedSize(it.payload.size).toLong() }
        if (apply) {
            writeRegion(file, kept)
            removeMirrors(worldDir, file.name, removedIndices)
        }
        return RegionResult(kept.size, removedIndices.size, bytes.size.toLong(), after)
    }

    /** Los mismos índices existen en `entities/` y `poi/`; se quitan para no dejar huérfanos. */
    private fun removeMirrors(worldDir: File, name: String, indices: Set<Int>) {
        if (indices.isEmpty()) return
        listOf("entities", "poi").forEach { folder ->
            val mirror = File(File(worldDir, folder), name)
            if (!mirror.exists()) return@forEach
            val bytes = mirror.readBytes()
            if (bytes.size < HEADER_BYTES) return@forEach
            val kept = (0 until CHUNKS_PER_REGION).mapNotNull { readChunk(bytes, it) }.filter { it.index !in indices }
            writeRegion(mirror, kept)
        }
    }

    private fun isUnvisited(chunk: Chunk): Boolean {
        val nbt = decompress(chunk) ?: return false
        // Sin `InhabitedTime` se conserva (conservador).
        return Nbt.findLong(ByteArrayInputStream(nbt), "InhabitedTime") == 0L
    }

    private fun readChunk(bytes: ByteArray, index: Int): Chunk? {
        val location = index * 4
        val offset = ((bytes[location].toInt() and 0xFF) shl 16) or
            ((bytes[location + 1].toInt() and 0xFF) shl 8) or
            (bytes[location + 2].toInt() and 0xFF)
        val sectors = bytes[location + 3].toInt() and 0xFF
        if (offset == 0 || sectors == 0) return null
        val start = offset * SECTOR_SIZE
        if (start + 5 > bytes.size) return null
        val length = readInt(bytes, start)
        if (length <= 0 || start + 4 + length > bytes.size) return null
        val compression = bytes[start + 4].toInt() and 0xFF
        val payload = bytes.copyOfRange(start + 5, start + 4 + length)
        return Chunk(index, compression, payload, readInt(bytes, SECTOR_SIZE + index * 4))
    }

    private fun writeRegion(file: File, chunks: List<Chunk>) {
        val header = ByteArray(HEADER_BYTES)
        val entries = ArrayList<ByteArray>(chunks.size)
        var sector = 2
        for (chunk in chunks) {
            val byteLength = 1 + chunk.payload.size
            val sectors = (4 + byteLength + SECTOR_SIZE - 1) / SECTOR_SIZE
            val location = chunk.index * 4
            header[location] = ((sector shr 16) and 0xFF).toByte()
            header[location + 1] = ((sector shr 8) and 0xFF).toByte()
            header[location + 2] = (sector and 0xFF).toByte()
            header[location + 3] = sectors.toByte()
            writeInt(header, SECTOR_SIZE + chunk.index * 4, chunk.timestamp)

            val entry = ByteArray(sectors * SECTOR_SIZE)
            writeInt(entry, 0, byteLength)
            entry[4] = chunk.compression.toByte()
            System.arraycopy(chunk.payload, 0, entry, 5, chunk.payload.size)
            entries.add(entry)
            sector += sectors
        }
        file.outputStream().buffered().use { out ->
            out.write(header)
            entries.forEach { out.write(it) }
        }
    }

    private fun paddedSize(payloadSize: Int): Int {
        val total = 4 + 1 + payloadSize
        return (total + SECTOR_SIZE - 1) / SECTOR_SIZE * SECTOR_SIZE
    }

    private fun decompress(chunk: Chunk): ByteArray? = runCatching {
        when (chunk.compression) {
            1 -> GZIPInputStream(ByteArrayInputStream(chunk.payload)).readBytes()
            2 -> InflaterInputStream(ByteArrayInputStream(chunk.payload)).readBytes()
            3 -> chunk.payload
            else -> null
        }
    }.getOrNull()

    private fun cleanLogs(dir: File, apply: Boolean): Pair<Int, Long> {
        val cutoff = System.currentTimeMillis() - LOG_KEEP_MS
        val old = File(dir, "logs").listFiles { file ->
            file.isFile && file.name.endsWith(".log.gz") && file.lastModified() < cutoff
        } ?: return 0 to 0L
        var bytes = 0L
        old.forEach { log ->
            bytes += log.length()
            if (apply) log.delete()
        }
        return old.size to bytes
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private fun writeInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = ((value ushr 24) and 0xFF).toByte()
        bytes[at + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[at + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[at + 3] = (value and 0xFF).toByte()
    }
}
