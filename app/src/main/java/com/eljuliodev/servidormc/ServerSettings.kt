package com.eljuliodev.servidormc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * Ajustes de un servidor persistidos en `server.properties` (y `server-icon.png`).
 *
 * Se editan en la pestaña "Ajustes" y Minecraft los relee al arrancar, así que se aplican la
 * próxima vez que se enciende el servidor. Las claves desconocidas del archivo se conservan.
 */
data class ServerSettings(
    val motd: String = "NomadServer",
    val maxPlayers: Int = 20,
    val gamemode: String = "survival",
    val difficulty: String = "easy",
    val allowFlight: Boolean = false,
    val whitelist: Boolean = false,
    val cracked: Boolean = false,
    val spawnProtection: Int = 16,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("motd", motd)
        put("maxPlayers", maxPlayers)
        put("gamemode", gamemode)
        put("difficulty", difficulty)
        put("allowFlight", allowFlight)
        put("whitelist", whitelist)
        put("cracked", cracked)
        put("spawnProtection", spawnProtection)
    }

    companion object {
        val GAMEMODES = listOf("survival", "creative", "spectator")
        val DIFFICULTIES = listOf("peaceful", "easy", "normal", "hard")
        const val ICON_SIZE = 64

        /** Normaliza lo que manda la web: valores raros caen a un default válido. */
        fun fromJson(json: JSONObject) = ServerSettings(
            motd = json.optString("motd", "NomadServer").take(200),
            maxPlayers = json.optInt("maxPlayers", 20).coerceIn(1, 100),
            gamemode = json.optString("gamemode", "survival").let { if (it in GAMEMODES) it else "survival" },
            difficulty = json.optString("difficulty", "easy").let { if (it in DIFFICULTIES) it else "easy" },
            allowFlight = json.optBoolean("allowFlight", false),
            whitelist = json.optBoolean("whitelist", false),
            cracked = json.optBoolean("cracked", false),
            spawnProtection = json.optInt("spawnProtection", 16).coerceIn(0, 1000),
        )

        fun read(dir: File): ServerSettings {
            val file = File(dir, "server.properties")
            if (!file.exists()) return ServerSettings()
            val map = HashMap<String, String>()
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val eq = line.indexOf('=')
                if (eq > 0) map[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
            return ServerSettings(
                motd = map["motd"] ?: "NomadServer",
                maxPlayers = map["max-players"]?.toIntOrNull() ?: 20,
                gamemode = map["gamemode"]?.lowercase() ?: "survival",
                difficulty = map["difficulty"]?.lowercase() ?: "easy",
                allowFlight = map["allow-flight"] == "true",
                whitelist = map["white-list"] == "true",
                cracked = map["online-mode"] == "false",
                spawnProtection = map["spawn-protection"]?.toIntOrNull() ?: 16,
            )
        }

        fun write(dir: File, settings: ServerSettings) {
            val values = linkedMapOf(
                "motd" to settings.motd,
                "max-players" to settings.maxPlayers.toString(),
                "gamemode" to settings.gamemode,
                "difficulty" to settings.difficulty,
                "allow-flight" to settings.allowFlight.toString(),
                "white-list" to settings.whitelist.toString(),
                "enforce-whitelist" to settings.whitelist.toString(),
                "online-mode" to (!settings.cracked).toString(),
                "spawn-protection" to settings.spawnProtection.toString(),
            )
            val file = File(dir, "server.properties")
            val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
            val pending = LinkedHashMap(values)
            for (i in lines.indices) {
                val key = lines[i].trim().substringBefore('=')
                if (lines[i].trim().startsWith("#") || key.isEmpty()) continue
                pending.remove(key)?.let { lines[i] = "$key=$it" }
            }
            pending.forEach { (key, value) -> lines.add("$key=$value") }
            dir.mkdirs()
            file.writeText(lines.joinToString("\n") + "\n")
        }

        /** Guarda `server-icon.png` (64x64) desde un data URL `data:image/…;base64,…`. */
        fun saveIcon(dir: File, dataUrl: String): Boolean {
            val payload = dataUrl.substringAfter("base64,", dataUrl)
            val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return false
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            val side = minOf(decoded.width, decoded.height)
            if (side <= 0) return false
            val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, ICON_SIZE, ICON_SIZE, true)
            val ok = runCatching {
                dir.mkdirs()
                FileOutputStream(File(dir, "server-icon.png")).use {
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }.isSuccess
            if (square !== decoded) square.recycle()
            if (scaled !== square) scaled.recycle()
            decoded.recycle()
            return ok
        }
    }
}
