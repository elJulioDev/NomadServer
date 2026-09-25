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
            )
        }
    }

    fun add(context: Context, name: String, ramMb: Int, maxPlayers: Int = 20): ServerProfile {
        val profile = ServerProfile(id = UUID.randomUUID().toString(), name = name, ramMb = ramMb, maxPlayers = maxPlayers)
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

    private fun save(context: Context, profiles: List<ServerProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("ramMb", p.ramMb)
                    put("maxPlayers", p.maxPlayers)
                },
            )
        }
        File(context.filesDir, FILE).writeText(array.toString())
    }
}
