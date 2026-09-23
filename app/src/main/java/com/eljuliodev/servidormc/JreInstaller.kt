package com.eljuliodev.servidormc

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Unpacks the bundled JRE (assets/jre.zip) into filesDir on first use.
 *
 * The JRE keeps its normal layout here (`bin/java`, `lib/modules`, `lib/server/libjvm.so`), so
 * `ServerManager` can just run `<filesDir>/jre/bin/java` via ProcessBuilder — possible because the
 * app targets API 28 (see build.gradle.kts: W^X forbids exec from app data at targetSdk >= 29).
 */
object JreInstaller {

    /** Bump when the packaged JRE changes so existing installs are replaced. */
    private const val VERSION = "jre25.0.5"

    fun ensure(context: Context, log: (String) -> Unit = {}): File {
        val home = File(context.filesDir, "jre")
        val stamp = File(home, ".installed")
        if (stamp.exists() && stamp.readText().trim() == VERSION) return home

        log("Instalando JRE (solo la primera vez)…")
        home.deleteRecursively()
        home.mkdirs()
        val root = home.canonicalPath + File.separator
        context.assets.open(ZIP).use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val out = File(home, entry.name)
                    check(out.canonicalPath == home.canonicalPath || out.canonicalPath.startsWith(root)) {
                        "entrada insegura: ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        check(File(home, "release").exists()) { "jre.zip incompleto" }
        // Zip entries don't carry the exec bit; the launcher (and jspawnhelper) must be runnable.
        File(home, "bin").listFiles()?.forEach { it.setExecutable(true) }
        File(home, "lib/jspawnhelper").takeIf { it.exists() }?.setExecutable(true)
        stamp.writeText(VERSION)
        log("JRE listo en ${home.absolutePath}")
        return home
    }

    private const val ZIP = "jre.zip"
}
