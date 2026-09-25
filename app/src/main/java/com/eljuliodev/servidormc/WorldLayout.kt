package com.eljuliodev.servidormc

import java.io.File

/**
 * Layout del mundo de Minecraft. Soporta los dos formatos:
 *
 * - **Clásico**: el overworld en `<level>/` (`region`, `entities`, `poi`, `data`), el Nether y
 *   el End en `<level>/DIM-1` y `<level>/DIM1` (vanilla) o en `<level>_nether` y
 *   `<level>_the_end` (Bukkit/Paper).
 * - **26.1+**: todo bajo `<level>/dimensions/<namespace>/<dimensión>/`, p. ej.
 *   `<level>/dimensions/minecraft/overworld/region`.
 */
object WorldLayout {

    const val NETHER = "nether"
    const val END = "end"

    data class Dimension(val label: String, val dir: File)

    /** Carpetas que son un "level" (tienen `level.dat`). */
    fun levels(root: File): List<File> =
        root.listFiles { file -> file.isDirectory && File(file, "level.dat").isFile }?.toList().orEmpty()

    /** Carpetas llamadas `region` que contienen `.mca`, a cualquier profundidad razonable. */
    fun regionDirs(root: File, maxDepth: Int = 6): List<File> {
        val result = ArrayList<File>()
        fun walk(current: File, depth: Int) {
            if (depth > maxDepth) return
            (current.listFiles() ?: return).forEach { child ->
                if (!child.isDirectory) return@forEach
                if (child.name == "region") {
                    if (child.listFiles { it.name.endsWith(".mca") }?.isNotEmpty() == true) result.add(child)
                } else {
                    walk(child, depth + 1)
                }
            }
        }
        walk(root, 1)
        return result
    }

    /** Dimensiones detectadas a partir de sus `region/`: etiqueta + carpeta de la dimensión. */
    fun dimensions(root: File): List<Dimension> {
        val seen = LinkedHashMap<String, File>()
        regionDirs(root).forEach { region ->
            val dir = region.parentFile ?: return@forEach
            // El overworld clásico usa la carpeta del level; los demás, el nombre de la dimensión.
            val name = if (File(dir, "level.dat").isFile) "overworld" else dir.name
            seen.putIfAbsent(label(name), dir)
        }
        return seen.map { Dimension(it.key, it.value) }
    }

    /** Posibles carpetas de una dimensión en todos los layouts (para regenerarla). */
    fun candidates(level: File, dimension: String): List<File> {
        val dimensions = File(File(level, "dimensions"), "minecraft")
        return when (dimension) {
            NETHER -> listOf(
                File(level, "DIM-1"),
                File(level.parentFile, "${level.name}_nether"),
                File(dimensions, "the_nether"),
            )
            END -> listOf(
                File(level, "DIM1"),
                File(level.parentFile, "${level.name}_the_end"),
                File(dimensions, "the_end"),
            )
            else -> emptyList()
        }
    }

    /** Nombre legible de una dimensión según el nombre de su carpeta. */
    fun label(name: String): String = when {
        name == "overworld" || name == "world" -> "Mundo"
        name == "the_nether" || name == "DIM-1" || name.endsWith("_nether") -> "Nether"
        name == "the_end" || name == "DIM1" || name.endsWith("_the_end") -> "End"
        else -> name
    }
}
