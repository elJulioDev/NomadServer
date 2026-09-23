package com.eljuliodev.servidormc

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Integration check for the JRE packaging (needs a device): extracts assets/jre.zip and runs the
 * embedded `java -version` via ProcessBuilder. Works because the app targets API 28
 * (at targetSdk >= 29 W^X forbids executing files from filesDir).
 */
class JreSmokeTest {

    @Test
    fun bundledJavaRuns() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val home = JreInstaller.ensure(ctx)
        val java = File(home, "bin/java")
        assertTrue("bin/java no existe", java.exists())

        val pb = ProcessBuilder(java.absolutePath, "-version").redirectErrorStream(true)
        pb.environment()["JAVA_HOME"] = home.absolutePath
        pb.environment()["LD_LIBRARY_PATH"] = "${home}/lib:${home}/lib/server"
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        println("JRESMOKE exit=$exit out=$output")

        assertEquals("salida de java -version: $output", 0, exit)
        assertTrue("no parece un JRE 25: $output", output.contains("version \"25"))
    }
}
