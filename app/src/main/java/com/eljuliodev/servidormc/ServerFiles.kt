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
        log: (String) -> Unit = {},
    ) {
        val file = File(dir, "server.properties")
        if (file.exists()) return
        file.writeText(
            """
            |motd=NomadServer
            |white-list=false
            |enforce-whitelist=false
            |view-distance=$viewDistance
            |simulation-distance=$simulationDistance
            |sync-chunk-writes=false
            |""".trimMargin(),
        )
        log("server.properties creado (view-distance=$viewDistance, simulation-distance=$simulationDistance)")
    }

    /** Downloads the latest vanilla `server.jar` if it isn't there yet and returns it. */
    fun ensureServerJar(dir: File, log: (String) -> Unit = {}): File {
        val jar = File(dir, "server.jar")
        if (jar.exists()) return jar
        log("Consultando la última versión de Minecraft…")
        val versionUrl = latestReleaseVersionUrl()
        val serverUrl = JSONObject(fetch(versionUrl))
            .getJSONObject("downloads")
            .getJSONObject("server")
            .getString("url")
        log("Descargando server.jar…")
        download(serverUrl, jar)
        log("server.jar listo (${jar.length() / 1_048_576} MB)")
        return jar
    }

    private fun latestReleaseVersionUrl(): String {
        val manifest = JSONObject(fetch(VERSION_MANIFEST))
        val latest = manifest.getJSONObject("latest").getString("release")
        val versions = manifest.getJSONArray("versions")
        for (i in 0 until versions.length()) {
            val version = versions.getJSONObject(i)
            if (version.getString("id") == latest) return version.getString("url")
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
