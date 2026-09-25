package com.eljuliodev.servidormc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Guarda la lista de perfiles de servidor como un JSON pequeño en filesDir. */
object ServerProfileStore {

    private const val FILE = "servers.json"

    fun list(context: Context): List<ServerProfile> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            ServerProfile(
                id = o.getString("id"),
                name = o.getString("name"),
                ramMb = o.getInt("ramMb"),
                maxPlayers = o.optInt("maxPlayers", 20),
                mcVersion = o.optString("mcVersion").takeIf { it.isNotEmpty() },
            )
        }
    }

    fun add(
        context: Context,
        name: String,
        ramMb: Int,
        maxPlayers: Int = 20,
        mcVersion: String? = null,
    ): ServerProfile {
        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            ramMb = ramMb,
            maxPlayers = maxPlayers,
            mcVersion = mcVersion,
        )
        save(context, list(context) + profile)
        return profile
    }

    fun remove(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    /** Mantiene el perfil en sincronía cuando cambian los slots desde "Ajustes". */
    fun setMaxPlayers(context: Context, id: String, maxPlayers: Int) {
        save(context, list(context).map { if (it.id == id) it.copy(maxPlayers = maxPlayers) else it })
    }

    /** La RAM elegida en "Ajustes" se guarda al arrancar para que sobreviva a reiniciar la app. */
    fun setRamMb(context: Context, id: String, ramMb: Int) {
        save(context, list(context).map { if (it.id == id) it.copy(ramMb = ramMb) else it })
    }

    /** Cambia la versión de Minecraft del perfil (se descarga al próximo arranque). */
    fun setVersion(context: Context, id: String, version: String?) {
        save(context, list(context).map { if (it.id == id) it.copy(mcVersion = version) else it })
    }

    private fun save(context: Context, profiles: List<ServerProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("ramMb", p.ramMb)
                    put("maxPlayers", p.maxPlayers)
                    put("mcVersion", p.mcVersion ?: "")
                },
            )
        }
        File(context.filesDir, FILE).writeText(array.toString())
    }
}
