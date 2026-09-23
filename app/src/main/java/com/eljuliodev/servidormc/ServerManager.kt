package com.eljuliodev.servidormc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Dueño del proceso de un servidor Minecraft y su estado para la UI.
 *
 * Una instancia por [serverId] — sus archivos viven en `filesDir/servers/<serverId>/`, aislados
 * de otros servidores. El JRE es compartido (se extrae una sola vez en `filesDir/jre`).
 *
 * ponytail: vive en la Application, no en un foreground service todavía — Fase 4 lo mueve a un
 * foreground service para que Android no lo mate en background.
 */
class ServerManager(private val context: Context, private val serverId: String) {

    enum class Status { Stopped, Starting, Running, Stopping, Error }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var process: Process? = null

    @Volatile
    private var requestedStop = false

    private val serverDir: File
        get() = File(context.filesDir, "servers/$serverId").apply { mkdirs() }

    /** El `java` del JRE, compartido por todos los servidores. */
    private val javaBinary: File
        get() = File(context.filesDir, "jre/bin/java")

    fun start(ramMb: Int) {
        if (_status.value == Status.Starting || _status.value == Status.Running) return
        requestedStop = false
        _status.value = Status.Starting
        scope.launch {
            try {
                // Extrae el JRE en el primer arranque (tarda unos segundos).
                val jreHome = JreInstaller.ensure(context, log = ::log)
                if (!javaBinary.exists()) {
                    log("JRE no encontrado en ${javaBinary.absolutePath}")
                    _status.value = Status.Error
                    return@launch
                }
                val dir = serverDir
                ServerFiles.ensureEula(dir)
                ServerFiles.ensureProperties(dir, log = ::log)
                val jar = ServerFiles.ensureServerJar(dir, log = ::log)

                val pb = ProcessBuilder(
                    javaBinary.absolutePath,
                    "-Djava.io.tmpdir=${File(context.cacheDir, "jre-tmp").apply { mkdirs() }}",
                    "-Xmx${ramMb}M",
                    "-Xms${ramMb / 2}M",
                    "-jar",
                    jar.absolutePath,
                    "nogui",
                )
                    .directory(dir)
                    .redirectErrorStream(true)
                pb.environment()["JAVA_HOME"] = jreHome.absolutePath
                pb.environment()["LD_LIBRARY_PATH"] =
                    "${jreHome.absolutePath}/lib:${jreHome.absolutePath}/lib/server"

                log("Iniciando servidor (${ramMb} MB)…")
                val p = pb.start()
                process = p
                _status.value = Status.Running

                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        log(line)
                    }
                }
                val code = p.waitFor()
                log("Proceso terminado (código $code)")
                _status.value = if (requestedStop) Status.Stopped else Status.Error
            } catch (t: Throwable) {
                log("Error: ${t.javaClass.simpleName}: ${t.message}")
                _status.value = Status.Error
            } finally {
                process = null
            }
        }
    }

    fun stop() {
        val p = process ?: return
        if (_status.value != Status.Running && _status.value != Status.Starting) return
        requestedStop = true
        _status.value = Status.Stopping
        log("Deteniendo servidor…")
        runCatching {
            p.outputStream.bufferedWriter().apply {
                write("stop\n")
                flush()
            }
        }.onFailure { log("No se pudo enviar 'stop': ${it.message}") }

        scope.launch {
            delay(10_000)
            if (p.isAlive) {
                log("El servidor no respondió; forzando cierre")
                p.destroy()
            }
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        _logs.value = (_logs.value + line).takeLast(MAX_LOG_LINES)
    }

    companion object {
        private const val TAG = "ServerManager"
        private const val MAX_LOG_LINES = 2000
    }
}