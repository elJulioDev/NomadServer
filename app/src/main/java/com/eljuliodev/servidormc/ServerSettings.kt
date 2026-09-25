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
    /** `view-distance`: radio de chunks que se envían al cliente. */
    val viewDistance: Int = 6,
    /** `simulation-distance`: radio de chunks que el server "simula" (mobs, ticks). */
    val simulationDistance: Int = 4,
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
        put("viewDistance", viewDistance)
        put("simulationDistance", simulationDistance)
    }

    companion object {
        val GAMEMODES = listOf("survival", "creative", "spectator")
        val DIFFICULTIES = listOf("peaceful", "easy", "normal", "hard")
        const val ICON_SIZE = 64

        /** Rango que acepta vanilla para las dos distancias (chunks). */
        const val DISTANCE_MIN = 3
        const val DISTANCE_MAX = 32

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
            // Independientes: vanilla recorta la simulación a la visión al arrancar, pero aquí no
            // se pisa una con la otra (el usuario ajusta cada slider por su cuenta).
            viewDistance = json.optInt("viewDistance", 6).coerceIn(DISTANCE_MIN, DISTANCE_MAX),
            simulationDistance = json.optInt("simulationDistance", 4).coerceIn(DISTANCE_MIN, DISTANCE_MAX),
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
                motd = decodeValue(map["motd"] ?: "NomadServer"),
                maxPlayers = map["max-players"]?.toIntOrNull() ?: 20,
                gamemode = map["gamemode"]?.lowercase() ?: "survival",
                difficulty = map["difficulty"]?.lowercase() ?: "easy",
                allowFlight = map["allow-flight"] == "true",
                whitelist = map["white-list"] == "true",
                cracked = map["online-mode"] == "false",
                spawnProtection = map["spawn-protection"]?.toIntOrNull() ?: 16,
                viewDistance = (map["view-distance"]?.toIntOrNull() ?: 6)
                    .coerceIn(DISTANCE_MIN, DISTANCE_MAX),
                simulationDistance = (map["simulation-distance"]?.toIntOrNull() ?: 4)
                    .coerceIn(DISTANCE_MIN, DISTANCE_MAX),
            )
        }

        fun write(dir: File, settings: ServerSettings) {
            val values = linkedMapOf(
                "motd" to encodeValue(settings.motd),
                "max-players" to settings.maxPlayers.toString(),
                "gamemode" to settings.gamemode,
                "difficulty" to settings.difficulty,
                "allow-flight" to settings.allowFlight.toString(),
                "white-list" to settings.whitelist.toString(),
                "enforce-whitelist" to settings.whitelist.toString(),
                "online-mode" to (!settings.cracked).toString(),
                "spawn-protection" to settings.spawnProtection.toString(),
                "view-distance" to settings.viewDistance.toString(),
                "simulation-distance" to settings.simulationDistance.toString(),
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

        /**
         * `server.properties` lo lee Minecraft con `Properties.load`, que es ISO-8859-1 y
         * interpreta `\uXXXX` / `\n`. El MOTD lleva `§` (U+00A7, no Latin-1) y puede tener dos
         * líneas, así que se escapan al escribir y se desescapan al leer.
         */
        private fun encodeValue(value: String): String = buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '§' -> append("\\u00A7")
                    else -> append(ch)
                }
            }
        }

        private fun decodeValue(value: String): String {
            if ('\\' !in value) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch != '\\' || i + 1 >= value.length) {
                    out.append(ch)
                    i++
                    continue
                }
                when (val next = value[i + 1]) {
                    'u' -> {
                        val hex = value.substring(i + 2, minOf(i + 6, value.length))
                        val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                        if (code == null) {
                            out.append(ch)
                            i++
                        } else {
                            out.append(code.toChar())
                            i += 6
                        }
                    }
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'f' -> { out.append('\u000C'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    // Java descarta la barra si el escape no es válido.
                    else -> { out.append(next); i += 2 }
                }
            }
            return out.toString()
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
