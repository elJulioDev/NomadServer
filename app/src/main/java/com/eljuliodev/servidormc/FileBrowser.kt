package com.eljuliodev.servidormc

import java.io.File

/**
 * Navegación de solo lectura por el directorio de un servidor (`filesDir/servers/<id>/`).
 * [list] nunca sale de [root]: cualquier ruta con `..` se resuelve y se valida.
 */
object FileBrowser {

    data class Entry(val name: String, val directory: Boolean, val size: Long, val modified: Long)

    /** Carpetas primero, luego alfabético. Oculta la carpeta temporal de importación. */
    fun list(root: File, path: String): List<Entry> {
        val target = resolve(root, path) ?: return emptyList()
        return (target.listFiles() ?: return emptyList())
            .filter { it.name != "world-import" }
            .map { file ->
                Entry(
                    name = file.name,
                    directory = file.isDirectory,
                    size = if (file.isDirectory) 0L else file.length(),
                    modified = file.lastModified(),
                )
            }
            .sortedWith(compareByDescending<Entry> { it.directory }.thenBy { it.name.lowercase() })
    }

    /** Devuelve [path] dentro de [root], o null si intenta salir del directorio del servidor. */
    fun resolve(root: File, path: String): File? {
        val rootPath = root.canonicalPath
        val target = runCatching { File(root, path).canonicalFile }.getOrNull() ?: return null
        return target.takeIf { it.path == rootPath || it.path.startsWith(rootPath + File.separator) }
    }
}
