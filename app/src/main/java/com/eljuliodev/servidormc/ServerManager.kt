package com.eljuliodev.servidormc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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

    /** RAM residente real del proceso (MB), null si no se pudo leer o está apagado. */
    private val _ramUsedMb = MutableStateFlow<Int?>(null)
    val ramUsedMb: StateFlow<Int?> = _ramUsedMb.asStateFlow()

    /** Jugadores actualmente conectados, deducido de las líneas "joined/left the game". */
    private val _players = MutableStateFlow<Set<String>>(emptySet())
    val players: StateFlow<Set<String>> = _players.asStateFlow()

    private var process: Process? = null
    private var monitorJob: Job? = null

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
        _players.value = emptySet()
        _ramUsedMb.value = null
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
                monitorJob = scope.launch { monitorRam(p.pid()) }

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
                monitorJob?.cancel()
                monitorJob = null
                _ramUsedMb.value = null
                _players.value = emptySet()
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

    private suspend fun monitorRam(pid: Long) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            _ramUsedMb.value = readRssMb(pid)
            delay(5_000)
        }
    }

    private fun readRssMb(pid: Long): Int? = runCatching {
        File("/proc/$pid/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.trim()?.split(Regex("\\s+"))
            ?.get(1)?.toLong()?.div(1024)?.toInt()
    }.getOrNull()

    private val joinRegex = Regex(""": (\S+) joined the game""")
    private val leaveRegex = Regex(""": (\S+) left the game""")

    private fun log(line: String) {
        Log.i(TAG, line)
        _logs.value = (_logs.value + line).takeLast(MAX_LOG_LINES)
        joinRegex.find(line)?.let { m -> _players.value = _players.value + m.groupValues[1] }
        leaveRegex.find(line)?.let { m -> _players.value = _players.value - m.groupValues[1] }
    }

    companion object {
        private const val TAG = "ServerManager"
        private const val MAX_LOG_LINES = 2000
    }
}