package com.eljuliodev.servidormc

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Tamaños en bytes de las partes del servidor y espacio libre del dispositivo. */
data class WorldSizes(
    val world: Long,
    val nether: Long,
    val end: Long,
    val jar: Long,
    val logs: Long,
    val total: Long,
    val free: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("world", world)
        put("nether", nether)
        put("end", end)
        put("jar", jar)
        put("logs", logs)
        put("total", total)
        put("free", free)
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
        fun sizeOf(path: String): Long {
            val file = File(dir, path)
            if (!file.exists()) return 0L
            return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        return WorldSizes(
            world = sizeOf(name),
            nether = sizeOf("${name}_nether"),
            end = sizeOf("${name}_the_end"),
            jar = File(dir, "server.jar").takeIf { it.exists() }?.length() ?: 0L,
            logs = sizeOf("logs"),
            total = sizeOf("."),
            free = freeBytes,
        )
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
    fun importZip(dir: File, zip: InputStream): Result<Unit> = runCatching {
        val tmp = File(dir, "world-import").apply { deleteRecursively(); mkdirs() }
        try {
            extractSafe(zip, tmp)
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

    private fun extractSafe(zip: InputStream, dest: File) {
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
