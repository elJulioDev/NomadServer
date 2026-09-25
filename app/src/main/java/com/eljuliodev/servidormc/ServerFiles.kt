package com.eljuliodev.servidormc

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Una versión de Minecraft del manifest de Mojang. */
data class McVersion(val id: String, val type: String, val releaseTime: String)

/** Everything that lives in the server directory: config files and the vanilla server jar. */
object ServerFiles {

    private const val VERSION_MANIFEST =
        "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"

    /** Versión mínima soportada: el JRE empaquetado es jre25 (ver AGENTS.md). */
    private const val MIN_VERSION = "1.17"

    /** Nombre del archivo donde se cachea el manifest en `cacheDir`. */
    const val MANIFEST_CACHE_NAME = "version_manifest.json"

    private const val MANIFEST_TTL_MS = 60 * 60 * 1000L
    private var manifestCache: Pair<Long, String>? = null

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

    /**
     * Descarga el `server.jar` de [version] (o de la última release si es null) si no está ya.
     * Si cambia la versión pedida respecto de `version.txt`, re-descarga.
     */
    fun ensureServerJar(
        dir: File,
        version: String? = null,
        cacheFile: File? = null,
        log: (String) -> Unit = {},
    ): File {
        val jar = File(dir, "server.jar")
        val current = File(dir, "version.txt").takeIf { it.exists() }?.readText()?.trim()
        if (!needsJarDownload(jar.exists(), current, version)) return jar

        val versionId = version ?: latestRelease(cacheFile)
        log(if (version == null) "Consultando la última versión de Minecraft…" else "Preparando Minecraft $version…")
        val serverUrl = JSONObject(fetch(versionUrlOf(versionId, cacheFile)))
            .getJSONObject("downloads")
            .getJSONObject("server")
            .getString("url")
        log("Descargando server.jar…")
        jar.delete()
        download(serverUrl, jar)
        File(dir, "version.txt").writeText(versionId)
        log("server.jar listo ($versionId, ${jar.length() / 1_048_576} MB)")
        return jar
    }

    /**
     * ¿Hay que (re)descargar el jar? Sólo si falta, o si se pidió una versión distinta de la
     * instalada. Sin versión fijada se respeta lo instalado (no perseguir "la última" siempre).
     */
    internal fun needsJarDownload(jarExists: Boolean, installed: String?, requested: String?): Boolean =
        !(jarExists && !installed.isNullOrEmpty() && (requested == null || installed == requested))

    /** Versiones vanilla (releases) soportadas por el JRE empaquetado, de nueva a vieja. */
    fun listVersions(cacheFile: File? = null): List<McVersion> {
        val versions = JSONObject(fetchManifest(cacheFile)).getJSONArray("versions")
        return buildList {
            for (i in 0 until versions.length()) {
                val v = versions.getJSONObject(i)
                if (v.getString("type") != "release") continue
                val id = v.getString("id")
                if (!isAtLeast(id, MIN_VERSION)) continue
                add(McVersion(id, "release", v.optString("releaseTime")))
            }
        }
    }

    /** Versión de Minecraft instalada en [dir] (leída de `version.txt`), o null si aún no se descarga. */
    fun installedVersion(dir: File): String? =
        File(dir, "version.txt").takeIf { it.exists() }?.readText()?.trim()

    private fun latestRelease(cacheFile: File?): String =
        JSONObject(fetchManifest(cacheFile)).getJSONObject("latest").getString("release")

    private fun versionUrlOf(id: String, cacheFile: File?): String {
        val versions = JSONObject(fetchManifest(cacheFile)).getJSONArray("versions")
        for (i in 0 until versions.length()) {
            val version = versions.getJSONObject(i)
            if (version.getString("id") == id) return version.getString("url")
        }
        error("Versión $id no encontrada en el manifest")
    }

    /** Manifest de Mojang: memoria (1 h) → archivo ([cacheFile], 1 h) → red. */
    private fun fetchManifest(cacheFile: File? = null): String {
        val now = System.currentTimeMillis()
        manifestCache?.let { (at, body) -> if (now - at < MANIFEST_TTL_MS) return body }
        if (cacheFile != null && cacheFile.exists() && now - cacheFile.lastModified() < MANIFEST_TTL_MS) {
            val body = runCatching { cacheFile.readText() }.getOrNull()
            if (!body.isNullOrEmpty()) {
                manifestCache = now to body
                return body
            }
        }
        return fetch(VERSION_MANIFEST).also { body ->
            manifestCache = now to body
            if (cacheFile != null) {
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    cacheFile.writeText(body)
                }
            }
        }
    }

    /** Compara versiones tipo "1.21.4" por partes numéricas (los snapshots no llegan acá). */
    internal fun isAtLeast(id: String, min: String): Boolean {
        val a = id.split('.').mapNotNull { it.toIntOrNull() }
        val b = min.split('.').mapNotNull { it.toIntOrNull() }
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
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
