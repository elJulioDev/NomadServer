package com.eljuliodev.servidormc

import org.json.JSONArray
import java.io.File

/**
 * Lectura de las listas de jugadores del servidor. Las escrituras las hace Minecraft al
 * ejecutar los comandos (`op`, `whitelist add`, `ban`…), no la app.
 */
object PlayersStore {

    fun ops(dir: File): List<String> = entries(File(dir, "ops.json"), "name")

    fun whitelist(dir: File): List<String> = entries(File(dir, "whitelist.json"), "name")

    fun bannedIps(dir: File): List<String> = entries(File(dir, "banned-ips.json"), "ip")

    private fun entries(file: File, key: String): List<String> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                array.getJSONObject(i).optString(key).takeIf { it.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }
}
