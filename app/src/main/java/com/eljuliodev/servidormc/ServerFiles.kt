package com.eljuliodev.servidormc

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Everything that lives in the server directory: config files and the vanilla server jar. */
object ServerFiles {

    private const val VERSION_MANIFEST =
        "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"

    fun ensureEula(dir: File) {
        val file = File(dir, "eula.txt")
        if (!file.exists()) file.writeText("eula=true\n")
    }

    fun ensureProperties(
        dir: File,
        viewDistance: Int = 6,
        simulationDistance: Int = 4,
        maxPlayers: Int = 20,
        log: (String) -> Unit = {},
    ) {
        val defaults = linkedMapOf(
            "motd" to "NomadServer",
            "white-list" to "false",
            "enforce-whitelist" to "false",
            "view-distance" to viewDistance.toString(),
            "simulation-distance" to simulationDistance.toString(),
            "max-players" to maxPlayers.toString(),
            "sync-chunk-writes" to "false",
        )
        val file = File(dir, "server.properties")
        if (!file.exists()) {
            dir.mkdirs()
            file.writeText(defaults.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
            log("server.properties creado (view-distance=$viewDistance, simulation-distance=$simulationDistance, max-players=$maxPlayers)")
            return
        }
        // El archivo puede existir ya (se guardó al configurar el servidor): se respeta lo que
        // eligió el usuario y sólo se completan las claves que falten (rendimiento móvil).
        val lines = file.readLines().toMutableList()
        val present = lines.map { it.trim().substringBefore('=') }.toSet()
        val missing = defaults.filterKeys { it !in present }
        if (missing.isEmpty()) return
        lines.addAll(missing.map { (key, value) -> "$key=$value" })
        file.writeText(lines.joinToString("\n") + "\n")
        log("server.properties: añadidas ${missing.keys.joinToString()} (rendimiento móvil)")
    }

    /** Downloads the latest vanilla `server.jar` if it isn't there yet and returns it. */
    fun ensureServerJar(dir: File, log: (String) -> Unit = {}): File {
        val jar = File(dir, "server.jar")
        if (jar.exists()) return jar
        log("Consultando la última versión de Minecraft…")
        val (versionId, versionUrl) = latestRelease()
        val serverUrl = JSONObject(fetch(versionUrl))
            .getJSONObject("downloads")
            .getJSONObject("server")
            .getString("url")
        log("Descargando server.jar…")
        download(serverUrl, jar)
        File(dir, "version.txt").writeText(versionId)
        log("server.jar listo (${jar.length() / 1_048_576} MB)")
        return jar
    }

    /** Versión de Minecraft instalada en [dir] (leída de `version.txt`), o null si aún no se descarga. */
    fun installedVersion(dir: File): String? =
        File(dir, "version.txt").takeIf { it.exists() }?.readText()?.trim()

    private fun latestRelease(): Pair<String, String> {
        val manifest = JSONObject(fetch(VERSION_MANIFEST))
        val latest = manifest.getJSONObject("latest").getString("release")
        val versions = manifest.getJSONArray("versions")
        for (i in 0 until versions.length()) {
            val version = versions.getJSONObject(i)
            if (version.getString("id") == latest) return latest to version.getString("url")
        }
        error("Versión $latest no encontrada en el manifest")
    }

    private fun download(url: String, dest: File) {
        val part = File(dest.parentFile, "${dest.name}.part")
        val conn = open(url)
        conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
    }

    private fun fetch(url: String): String = open(url).let { conn ->
        conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
}
