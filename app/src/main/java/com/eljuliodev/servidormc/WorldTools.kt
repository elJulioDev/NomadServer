package com.eljuliodev.servidormc

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Tamaños/nº de archivos de las partes del servidor y espacio libre del dispositivo. */
data class WorldSizes(
    val world: Long,
    val nether: Long,
    val end: Long,
    val jar: Long,
    val logs: Long,
    val total: Long,
    val free: Long,
    val worldFiles: Long,
    val netherFiles: Long,
    val endFiles: Long,
    val totalFiles: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("world", world)
        put("nether", nether)
        put("end", end)
        put("jar", jar)
        put("logs", logs)
        put("total", total)
        put("free", free)
        put("worldFiles", worldFiles)
        put("netherFiles", netherFiles)
        put("endFiles", endFiles)
        put("totalFiles", totalFiles)
    }
}

/**
 * Operaciones sobre los mundos del servidor: tamaño en disco, regenerar dimensiones e importar
 * un mundo desde un `.zip`. Todo asume el servidor apagado (lo garantiza la UI).
 */
object WorldTools {

    const val NETHER = "nether"
    const val END = "end"

    /** Límite de descompresión del `.zip` importado (protección básica contra zips enormes). */
    private const val MAX_IMPORT_BYTES = 4L * 1024 * 1024 * 1024

    /** Margen que se deja libre en el dispositivo al importar un mundo. */
    private const val FREE_MARGIN_BYTES = 64L * 1024 * 1024

    /** `level-name` de `server.properties` (por defecto "world"). */
    fun levelName(dir: File): String {
        val file = File(dir, "server.properties")
        if (!file.exists()) return "world"
        return file.readLines()
            .firstOrNull { it.trim().startsWith("level-name=") }
            ?.substringAfter('=')
            ?.trim()
            ?.ifEmpty { "world" }
            ?: "world"
    }

    /** Tamaño en bytes de cada parte del servidor + espacio libre del dispositivo. */
    fun sizes(dir: File, freeBytes: Long): WorldSizes {
        val name = levelName(dir)
        fun statOf(path: String): Pair<Long, Long> {
            val file = File(dir, path)
            if (!file.exists()) return 0L to 0L
            var bytes = 0L
            var files = 0L
            file.walkTopDown().forEach { child ->
                if (child.isFile) {
                    bytes += child.length()
                    files++
                }
            }
            return bytes to files
        }
        val (world, worldFiles) = statOf(name)
        val (nether, netherFiles) = statOf("${name}_nether")
        val (end, endFiles) = statOf("${name}_the_end")
        val (logs, _) = statOf("logs")
        val (total, totalFiles) = statOf(".")
        return WorldSizes(
            world = world,
            nether = nether,
            end = end,
            jar = File(dir, "server.jar").takeIf { it.exists() }?.length() ?: 0L,
            logs = logs,
            total = total,
            free = freeBytes,
            worldFiles = worldFiles,
            netherFiles = netherFiles,
            endFiles = endFiles,
            totalFiles = totalFiles,
        )
    }

    /**
     * Semilla del mundo leyendo `<level-name>/level.dat` (NBT comprimido con gzip → `Data.RandomSeed`).
     * Sirve con el servidor apagado, cuando no se puede usar el comando `/seed`.
     */
    fun seed(dir: File): Long? = runCatching {
        val level = File(File(dir, levelName(dir)), "level.dat")
        if (!level.exists()) return null
        DataInputStream(BufferedInputStream(GZIPInputStream(level.inputStream()))).use { data ->
            if (data.readUnsignedByte() != TAG_COMPOUND) return null
            readName(data) // nombre del compuesto raíz
            findSeed(data)
        }
    }.getOrNull()

    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12

    /** Recorre el compuesto raíz buscando `Data` → `RandomSeed`. */
    private fun findSeed(data: DataInputStream): Long? {
        while (true) {
            val type = data.readUnsignedByte()
            if (type == TAG_END) return null
            val name = readName(data)
            when {
                type == TAG_COMPOUND && name == "Data" -> return findSeed(data)
                type == TAG_LONG && name == "RandomSeed" -> return data.readLong()
                else -> skip(data, type)
            }
        }
    }

    private fun skipCompound(data: DataInputStream) {
        while (true) {
            val type = data.readUnsignedByte()
            if (type == TAG_END) return
            skipFully(data, data.readUnsignedShort())
            skip(data, type)
        }
    }

    private fun skip(data: DataInputStream, type: Int) {
        when (type) {
            TAG_BYTE -> skipFully(data, 1)
            TAG_SHORT -> skipFully(data, 2)
            TAG_INT, TAG_FLOAT -> skipFully(data, 4)
            TAG_LONG, TAG_DOUBLE -> skipFully(data, 8)
            TAG_BYTE_ARRAY -> skipFully(data, data.readInt())
            TAG_STRING -> skipFully(data, data.readUnsignedShort())
            TAG_LIST -> {
                val elementType = data.readUnsignedByte()
                val length = data.readInt()
                repeat(length) { skip(data, elementType) }
            }
            TAG_COMPOUND -> skipCompound(data)
            TAG_INT_ARRAY -> skipFully(data, data.readInt() * 4)
            TAG_LONG_ARRAY -> skipFully(data, data.readInt() * 8)
            else -> error("TAG de NBT desconocido: $type")
        }
    }

    private fun readName(data: DataInputStream): String {
        val bytes = ByteArray(data.readUnsignedShort())
        data.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipFully(data: DataInputStream, count: Int) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = data.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) error("NBT truncado")
            remaining -= read
        }
    }

    /**
     * Borra la carpeta de una dimensión para que Minecraft la regenere al arrancar.
     * Cubre el layout moderno (`<level>_nether` / `<level>_the_end`) y el viejo (`world/DIM-1`, `DIM1`).
     */
    fun regenerate(dir: File, dimension: String): Boolean {
        val name = levelName(dir)
        val targets = when (dimension) {
            NETHER -> listOf("${name}_nether", "$name/DIM-1")
            END -> listOf("${name}_the_end", "$name/DIM1")
            else -> return false
        }
        var deleted = false
        targets.forEach { relative ->
            val file = File(dir, relative)
            if (file.exists()) {
                file.deleteRecursively()
                deleted = true
            }
        }
        return deleted
    }

    /**
     * Reemplaza `<level-name>/` con el mundo del `.zip`. Acepta el `level.dat` en la raíz o dentro
     * de una única carpeta contenedora. Rechaza entradas que salgan del destino (zip-slip).
     */
    fun importZip(dir: File, zip: InputStream, freeBytes: Long = -1L): Result<Unit> = runCatching {
        val tmp = File(dir, "world-import").apply { deleteRecursively(); mkdirs() }
        try {
            extractSafe(zip, tmp, freeBytes)
            val root = worldRoot(tmp) ?: error("El .zip no contiene un mundo (falta level.dat)")
            val target = File(dir, levelName(dir))
            target.deleteRecursively()
            if (!root.renameTo(target)) {
                root.copyRecursively(target, overwrite = true)
                root.deleteRecursively()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun worldRoot(tmp: File): File? {
        if (File(tmp, "level.dat").exists()) return tmp
        val only = tmp.listFiles()?.filter { it.isDirectory }?.singleOrNull() ?: return null
        return only.takeIf { File(it, "level.dat").exists() }
    }

    private fun extractSafe(zip: InputStream, dest: File, freeBytes: Long) {
        val root = dest.canonicalPath + File.separator
        var written = 0L
        ZipInputStream(zip).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(root)) {
                    error("Entrada inválida en el .zip: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            written += read
                            if (written > MAX_IMPORT_BYTES) error("El .zip es demasiado grande")
                            if (freeBytes > 0 && written > freeBytes - FREE_MARGIN_BYTES) {
                                error("No hay espacio libre suficiente para importar el mundo")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }
}
