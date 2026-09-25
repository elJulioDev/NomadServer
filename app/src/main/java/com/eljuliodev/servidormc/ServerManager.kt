package com.eljuliodev.servidormc

import android.content.Context
import android.os.SystemClock
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
import kotlin.time.Duration.Companion.seconds

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

    /**
     * Líneas escritas en total, nunca decrece (la lista sí: es una ventana de 2000).
     * La UI lo usa para enviar sólo las líneas nuevas en cada push, en vez del log entero.
     */
    @Volatile
    var logTotal: Long = 0L
        private set

    /** RAM residente real del proceso (MB), null si no se pudo leer o está apagado. */
    private val _ramUsedMb = MutableStateFlow<Int?>(null)
    val ramUsedMb: StateFlow<Int?> = _ramUsedMb.asStateFlow()

    /** Jugadores actualmente conectados, deducido de las líneas "joined/left the game". */
    private val _players = MutableStateFlow<Set<String>>(emptySet())
    val players: StateFlow<Set<String>> = _players.asStateFlow()

    /** Progreso del arranque 0..100, de "Preparing spawn area: N%". 0 = aún preparando. */
    private val _startProgress = MutableStateFlow(0)
    val startProgress: StateFlow<Int> = _startProgress.asStateFlow()

    /** TPS 0..20 (estimados del log; ver [log]). null si está apagado. */
    private val _tps = MutableStateFlow<Double?>(null)
    val tps: StateFlow<Double?> = _tps.asStateFlow()

    /** Segundos que quedan antes del auto-apagado por inactividad; null si no aplica. */
    private val _autoStopSeconds = MutableStateFlow<Int?>(null)
    val autoStopSeconds: StateFlow<Int?> = _autoStopSeconds.asStateFlow()

    /** Semilla del mundo, leída del comando `/seed` (null si todavía no se pidió). */
    private val _seed = MutableStateFlow<String?>(null)
    val seed: StateFlow<String?> = _seed.asStateFlow()

    private var process: Process? = null
    private var monitorJob: Job? = null
    private var autoStopJob: Job? = null

    @Volatile
    private var autoStopDeadline = 0L

    @Volatile
    private var requestedStop = false

    @Volatile
    private var pendingRestart = false

    /** Evita repetir el aviso de "puerto ocupado" dentro del mismo arranque. */
    @Volatile
    private var portHintShown = false

    private val serverDir: File
        get() = File(context.filesDir, "servers/$serverId").apply { mkdirs() }

    /** El `java` del JRE, compartido por todos los servidores. */
    private val javaBinary: File
        get() = File(context.filesDir, "jre/bin/java")

    fun start(ramMb: Int, maxPlayers: Int = 20, mcVersion: String? = null) {
        if (_status.value == Status.Starting || _status.value == Status.Running) return
        requestedStop = false
        _status.value = Status.Starting
        _players.value = emptySet()
        _ramUsedMb.value = null
        _startProgress.value = 0
        _tps.value = null
        _autoStopSeconds.value = null
        _seed.value = null
        portHintShown = false
        scope.launch {
            try {
                // Un cierre forzado puede dejar vivo al hijo del bundler con el puerto tomado.
                killOrphans()
                // Extrae el JRE en el primer arranque (tarda unos segundos).
                val jreHome = JreInstaller.ensure(context, log = ::log)
                if (!javaBinary.exists()) {
                    log("JRE no encontrado en ${javaBinary.absolutePath}")
                    _status.value = Status.Error
                    return@launch
                }
                val dir = serverDir
                ServerFiles.ensureEula(dir)
                ServerFiles.ensureProperties(dir, maxPlayers = maxPlayers, log = ::log)
                val jar = ServerFiles.ensureServerJar(
                    dir,
                    mcVersion,
                    File(context.cacheDir, ServerFiles.MANIFEST_CACHE_NAME),
                    log = ::log,
                )

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
                // Sigue en Starting hasta que el log diga "Done (": así la UI muestra el progreso.
                monitorJob = scope.launch { monitorRam() }

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
                autoStopJob?.cancel()
                autoStopJob = null
                _autoStopSeconds.value = null
                _ramUsedMb.value = null
                _tps.value = null
                _players.value = emptySet()
                process = null
                // Un reinicio pendiente arranca de nuevo al terminar el proceso.
                if (pendingRestart) {
                    pendingRestart = false
                    start(ramMb, maxPlayers, mcVersion)
                }
            }
        }
    }

    /** Apaga y vuelve a encender (botón "Reiniciar"); si está apagado, sólo enciende. */
    fun restart(ramMb: Int, maxPlayers: Int = 20, mcVersion: String? = null) {
        if (_status.value != Status.Running && _status.value != Status.Starting) {
            start(ramMb, maxPlayers, mcVersion)
            return
        }
        pendingRestart = true
        log("Reiniciando servidor…")
        stop()
    }

    /** Escribe una línea en la consola del servidor desde la app (p. ej. progreso de importación). */
    fun note(message: String) {
        log("[NomadServer] $message")
    }

    /** Suma un minuto a la ventana de auto-apagado (como el botón de Aternos). */
    fun extendAutoStop() {
        if (_autoStopSeconds.value == null) return
        autoStopDeadline += AUTO_STOP_EXTRA_MS
    }

    /**
     * Escribe una línea en la consola del servidor (stdin), igual que el panel de Aternos.
     * Acepta comandos con o sin `/`; el server los resuelve como consola.
     */
    fun sendCommand(command: String) {
        val p = process ?: return
        if (_status.value != Status.Running && _status.value != Status.Starting) return
        val line = command.trim().trimStart('/').ifEmpty { return }
        runCatching {
            p.outputStream.bufferedWriter().apply {
                write("$line\n")
                flush()
            }
        }.onFailure { log("No se pudo enviar el comando: ${it.message}") }
    }

    fun stop() {
        val p = process ?: return
        if (_status.value != Status.Running && _status.value != Status.Starting) return
        requestedStop = true
        _status.value = Status.Stopping
        autoStopJob?.cancel()
        autoStopJob = null
        _autoStopSeconds.value = null
        log("Deteniendo servidor…")
        runCatching {
            p.outputStream.bufferedWriter().apply {
                write("stop\n")
                flush()
            }
        }.onFailure { log("No se pudo enviar 'stop': ${it.message}") }

        scope.launch {
            delay(10.seconds)
            if (p.isAlive) {
                log("El servidor no respondió; forzando cierre")
                p.destroy()
                // El bundler puede dejar vivo a su hijo; hay que matarlo para liberar el puerto.
                killOrphans()
            }
        }
    }

    private suspend fun monitorRam() {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            _ramUsedMb.value = readServerRssMb()
            delay(5.seconds)
        }
    }

    /**
     * RSS total (MB) de los procesos del server. El bundler de Minecraft lanza un segundo JVM
     * hijo, así que se suman todos los que apuntan a este [serverId].
     *
     * Se buscan en `/proc` y no con `Process.pid()` ni `ProcessHandle`: esas APIs Java 9 no
     * resuelven de forma fiable en este proyecto (ver AGENTS.md). Si el sistema no deja leer
     * `/proc`, el dato simplemente queda en null.
     */
    private fun readServerRssMb(): Int? {
        val pids = serverPids()
        if (pids.isEmpty()) return null
        val totalKb = pids.sumOf { readRssKb(it) ?: 0 }
        return if (totalKb == 0) null else totalKb / 1024
    }

    private fun serverPids(): List<Long> =
        File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }
            ?.mapNotNull { dir ->
                val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrDefault("")
                dir.name.toLongOrNull()?.takeIf { cmdline.contains("/servers/$serverId/") }
            }
            .orEmpty()

    /**
     * Mata procesos del server de este perfil que quedaran vivos (p. ej. el hijo del *bundler*
     * tras un cierre forzado). Sin esto, el puerto 25565 sigue tomado y el arranque falla con
     * "Address already in use".
     */
    private suspend fun killOrphans() {
        val pids = serverPids()
        if (pids.isEmpty()) return
        log("Cerrando ${pids.size} proceso(s) anterior(es) del servidor…")
        pids.forEach { pid ->
            runCatching { android.os.Process.sendSignal(pid.toInt(), android.os.Process.SIGNAL_KILL) }
        }
        // Deja que el sistema libere el puerto antes de arrancar de nuevo.
        delay(600)
    }

    private fun readRssKb(pid: Long): Int? = runCatching {
        File("/proc/$pid/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toIntOrNull()
    }.getOrNull()

    private val joinRegex = Regex(""": (\S+) joined the game""")
    private val leaveRegex = Regex(""": (\S+) left the game""")
    private val progressRegex = Regex("""Preparing spawn area: (\d+)%""")
    private val lagRegex = Regex("""Running \d+ms or (\d+) ticks behind""")
    private val seedRegex = Regex("""Seed: \[(-?\d+)\]""")

    @Synchronized
    private fun log(line: String) {
        Log.i(TAG, line)
        // El contador sube ANTES de publicar la lista: si el colector de la UI se reanuda en línea
        // (Main.immediate), ya ve el total nuevo y no se pierde la última línea.
        logTotal++
        _logs.value = (_logs.value + line).takeLast(MAX_LOG_LINES)
        joinRegex.find(line)?.let { m -> _players.value += m.groupValues[1] }
        leaveRegex.find(line)?.let { m -> _players.value -= m.groupValues[1] }
        progressRegex.find(line)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { _startProgress.value = it }
        }
        // ponytail: vanilla no expone TPS real; se aproxima desde "Can't keep up" (ticks atrasados).
        lagRegex.find(line)?.let { m ->
            val behind = m.groupValues[1].toIntOrNull() ?: 0
            _tps.value = (TPS_MAX - behind / 20.0).coerceIn(1.0, TPS_MAX)
        }
        seedRegex.find(line)?.let { m -> _seed.value = m.groupValues[1] }
        if (!portHintShown && line.contains("Address already in use")) {
            portHintShown = true
            log("[NomadServer] El puerto 25565 estaba ocupado: quedaba un servidor anterior. Toca Detener y vuelve a Iniciar.")
        }
        if (_status.value == Status.Starting && line.contains(DONE_MARKER)) {
            _startProgress.value = 100
            _tps.value = TPS_MAX
            _status.value = Status.Running
            startAutoStopWindow()
        }
    }

    /** Ventana de 2 min: si nadie entra, el server se apaga solo. Se cancela al entrar alguien. */
    private fun startAutoStopWindow() {
        autoStopJob?.cancel()
        autoStopDeadline = SystemClock.elapsedRealtime() + AUTO_STOP_MS
        _autoStopSeconds.value = (AUTO_STOP_MS / 1000).toInt()
        autoStopJob = scope.launch {
            while (isActive) {
                if (_players.value.isNotEmpty()) {
                    _autoStopSeconds.value = null
                    return@launch
                }
                val remaining = ((autoStopDeadline - SystemClock.elapsedRealtime()) / 1000).toInt()
                if (remaining <= 0) {
                    _autoStopSeconds.value = null
                    log("Nadie se conectó en ${AUTO_STOP_MS / 1000}s; apagando el servidor")
                    stop()
                    return@launch
                }
                _autoStopSeconds.value = remaining
                delay(1000)
            }
        }
    }

    companion object {
        private const val TAG = "ServerManager"
        private const val MAX_LOG_LINES = 2000
        private const val TPS_MAX = 20.0
        private const val DONE_MARKER = "Done ("
        private const val AUTO_STOP_MS = 120_000L
        private const val AUTO_STOP_EXTRA_MS = 60_000L
    }
}
