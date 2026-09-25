package com.eljuliodev.servidormc

import org.json.JSONArray
import java.io.File

/**
 * Lectura de las listas de jugadores del servidor. Las escrituras las hace Minecraft al
 * ejecutar los comandos (`op`, `whitelist add`, `ban`…), no la app.
 */
object PlayersStore {

    fun ops(dir: File): List<String> = names(File(dir, "ops.json"))

    fun whitelist(dir: File): List<String> = names(File(dir, "whitelist.json"))

    private fun names(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                array.getJSONObject(i).optString("name").takeIf { it.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }
}
