package com.eljuliodev.servidormc

import org.json.JSONObject
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
    /** Capacidad total del dispositivo (para calcular el usado). */
    val deviceTotal: Long,
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
        put("deviceTotal", deviceTotal)
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
    fun sizes(dir: File, freeBytes: Long, deviceTotalBytes: Long = 0L): WorldSizes {
        fun statOf(file: File): Pair<Long, Long> {
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

        val dims = WorldLayout.dimensions(dir).map { it to statOf(it.dir) }
        // Si el overworld clásico contiene DIM-1/DIM1 adentro, se restan para no contarlos dos veces.
        fun sizeFor(label: String): Pair<Long, Long> {
            val own = dims.filter { it.first.label == label }
            val bytes = own.sumOf { (dim, stat) -> stat.first - nestedSize(dims, dim, first = true) }
            val files = own.sumOf { (dim, stat) -> stat.second - nestedSize(dims, dim, first = false) }
            return bytes to files
        }

        val (world, worldFiles) = sizeFor("Mundo")
        val (nether, netherFiles) = sizeFor("Nether")
        val (end, endFiles) = sizeFor("End")
        val (logs, _) = statOf(File(dir, "logs"))
        val (total, totalFiles) = statOf(dir)
        return WorldSizes(
            world = world,
            nether = nether,
            end = end,
            jar = File(dir, "server.jar").takeIf { it.exists() }?.length() ?: 0L,
            logs = logs,
            total = total,
            free = freeBytes,
            deviceTotal = deviceTotalBytes,
            worldFiles = worldFiles,
            netherFiles = netherFiles,
            endFiles = endFiles,
            totalFiles = totalFiles,
        )
    }

    /** Tamaño de las dimensiones anidadas dentro de [dim] (evita doble conteo). */
    private fun nestedSize(
        dims: List<Pair<WorldLayout.Dimension, Pair<Long, Long>>>,
        dim: WorldLayout.Dimension,
        first: Boolean,
    ): Long = dims
        .filter { (other, _) -> other.dir != dim.dir && other.dir.path.startsWith(dim.dir.path + File.separator) }
        .sumOf { (_, stat) -> if (first) stat.first else stat.second }

    /**
     * Semilla del mundo leyendo `level.dat` (NBT comprimido con gzip → `Data.RandomSeed`).
     * Sirve con el servidor apagado, cuando no se puede usar el comando `/seed`.
     */
    fun seed(dir: File): Long? {
        val level = WorldLayout.levels(dir).firstOrNull() ?: return null
        val dat = File(level, "level.dat")
        if (!dat.exists()) return null
        return runCatching { GZIPInputStream(dat.inputStream()).use { Nbt.findLong(it, "RandomSeed") } }
            .getOrNull()
    }

    /**
     * Borra la carpeta de una dimensión para que Minecraft la regenere al arrancar.
     * Cubre el layout clásico (`<level>_nether`, `world/DIM-1`) y el 26.1+ (`dimensions/minecraft/...`).
     */
    fun regenerate(dir: File, dimension: String): Boolean {
        val levels = WorldLayout.levels(dir).ifEmpty { listOf(File(dir, levelName(dir))) }
        var deleted = false
        levels.forEach { level ->
            WorldLayout.candidates(level, dimension).forEach { candidate ->
                if (candidate.exists()) {
                    candidate.deleteRecursively()
                    deleted = true
                }
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
