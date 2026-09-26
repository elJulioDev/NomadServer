package com.eljuliodev.servidormc

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Arrays

/** Estado del túnel público de playit.gg, tal como lo pinta la UI. */
enum class PlayitState { Off, Preparing, Claiming, Running, Error }

data class PlayitInfo(
    val state: PlayitState = PlayitState.Off,
    /** Hay una Secret Key guardada (cuenta linkeada). */
    val linked: Boolean = false,
    /** Enlace que el usuario debe aprobar una vez en el navegador (flujo de claim). */
    val claimUrl: String? = null,
    /** Dirección pública (`xxx.craft.ply.gg`) una vez asignada. */
    val address: String? = null,
    val error: String? = null,
)

/**
 * Tuner público de playit.gg.
 *
 * Hay dos formas de vincular la cuenta:
 *  - **Claim (recomendado)**: `claim()` genera un código, registra el agente (`/claim/setup`) y
 *    abre `https://playit.gg/claim/<code>` (o expone el enlace) para que el usuario lo apruebe
 *    estando logueado; `awaitExchange()` sondea `/claim/exchange` hasta recibir la Secret Key.
 *  - **Secret Key pegada**: `link()` valida una key generada en `playit.gg/account/agents`.
 *
 * Con la key, el agente oficial (`playit-linux-aarch64`, estático y ejecutable en Android desde
 * `filesDir`, igual que el JRE) se lanza y el túnel Minecraft Java a `127.0.0.1:25565` se crea por
 * API. Dos ajustes del entorno Android:
 *  - El binario busca `/etc/resolv.conf`, que no existe y no se puede crear sin root. Se parchea
 *    ese literal a `dns.conf` (relativo) y el proceso corre con cwd aquí, donde escribimos los DNS
 *    del dispositivo.
 *  - La Secret Key se guarda en `playit.toml` y se pasa con `--secret_path`, sin depender de `HOME`.
 */
class PlayitManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _info = MutableStateFlow(PlayitInfo(linked = readSecret() != null))
    val info: StateFlow<PlayitInfo> = _info.asStateFlow()

    private var job: Job? = null
    private var process: Process? = null

    private val dir: File
        get() = File(context.filesDir, "playit").apply { mkdirs() }
    private val binary: File get() = File(dir, BINARY)
    private val secretFile: File get() = File(dir, "playit.toml")

    /** Inicia el claim: enlace para aprobar en el navegador y sondeo hasta recibir la key. */
    fun claim() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val code = randomCode()
                _info.value = PlayitInfo(state = PlayitState.Claiming, claimUrl = "https://playit.gg/claim/$code")
                post("/claim/setup", claimSetupBody(code), null)
                val secret = awaitExchange(code)
                withContext(Dispatchers.IO) { secretFile.writeText("secret_key = \"$secret\"\n") }
                runTunnel(secret)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _info.value = PlayitInfo(
                    state = PlayitState.Error,
                    linked = readSecret() != null,
                    error = messageOf(t),
                )
            }
        }
    }

    /** Linkea con una Secret Key pegada (generada en `playit.gg/account/agents`). */
    fun link(secret: String) {
        val clean = secret.trim()
        if (!isValidSecret(clean)) {
            _info.value = _info.value.copy(state = PlayitState.Error, error = "La Secret Key no parece válida")
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                _info.value = PlayitInfo(state = PlayitState.Preparing)
                rundata(clean)
                withContext(Dispatchers.IO) { secretFile.writeText("secret_key = \"$clean\"\n") }
                runTunnel(clean)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _info.value = PlayitInfo(state = PlayitState.Error, linked = false, error = messageOf(t))
            }
        }
    }

    /** Enciende el túnel con la key ya guardada. */
    fun start() {
        if (job?.isActive == true) return
        val secret = readSecret()
        if (secret == null) {
            _info.value = PlayitInfo(state = PlayitState.Error, error = "Linkea tu cuenta de playit.gg primero")
            return
        }
        _info.value = PlayitInfo(state = PlayitState.Preparing, linked = true)
        job = scope.launch {
            try {
                runTunnel(secret)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _info.value = PlayitInfo(state = PlayitState.Error, linked = true, error = messageOf(t))
            }
        }
    }

    /** Apaga el agente (el servidor de Minecraft sigue igual). */
    fun stop() {
        job?.cancel()
        job = null
        runCatching { process?.destroy() }
        process = null
        _info.value = PlayitInfo(state = PlayitState.Off, linked = readSecret() != null)
    }

    /** Olvida la cuenta (borra la Secret Key) y apaga el túnel. */
    fun unlink() {
        stop()
        secretFile.delete()
        _info.value = PlayitInfo()
    }

    fun dispose() = scope.cancel()

    /** Descarga/parchea el binario, crea el túnel y corre el agente. Bloquea hasta que muera. */
    private suspend fun runTunnel(secret: String) {
        ensureAgent()
        writeDns()
        _info.value = PlayitInfo(state = PlayitState.Running, linked = true)
        val tunnel = scope.launch { pollTunnel(secret) }
        try {
            runAgent()
        } finally {
            tunnel.cancel()
        }
        if (currentCoroutineContext().isActive) {
            _info.value = PlayitInfo(state = PlayitState.Off, linked = true)
        }
    }

    /** Sondea `/claim/exchange` hasta que el usuario apruebe en playit.gg. */
    private suspend fun awaitExchange(code: String): String {
        val deadline = System.currentTimeMillis() + CLAIM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
            val result = runCatching {
                post("/claim/exchange", JSONObject().put("code", code).toString(), null).getString("secret_key")
            }
            result.getOrNull()?.let { return it }
            val message = result.exceptionOrNull()?.message.orEmpty()
            if (message.contains("CodeExpired") || message.contains("UserRejected")) {
                error(friendly(result.exceptionOrNull()) ?: "El claim fue rechazado")
            }
            delay(2_000)
        }
        error("No se aprobó el agente en playit.gg")
    }

    private fun claimSetupBody(code: String): String = JSONObject().apply {
        put("code", code)
        put("agent_type", SELF_MANAGED)
        put("version", AGENT_VERSION)
    }.toString()

    /** Descarga y parchea el binario si falta o cambió de versión. */
    private fun ensureAgent() {
        val stamp = File(dir, "version.txt")
        if (binary.exists() && stamp.takeIf { it.exists() }?.readText()?.trim() == VERSION) return
        binary.delete()
        val part = File(dir, "$BINARY.part")
        part.delete()
        download(AGENT_URL, part)
        part.writeBytes(patchResolvPath(part.readBytes()))
        if (!part.renameTo(binary)) {
            part.copyTo(binary, overwrite = true)
            part.delete()
        }
        if (!binary.setExecutable(true)) error("No se pudo marcar el agente como ejecutable")
        stamp.writeText(VERSION)
    }

    /** DNS del dispositivo en `dns.conf` (relativo al cwd del proceso); con respaldo público. */
    private fun writeDns() {
        File(dir, "dns.conf").writeText(dnsServers().joinToString("") { "nameserver $it\n" })
    }

    private fun dnsServers(): List<String> {
        val servers = mutableListOf<String>()
        runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return@runCatching
            manager.getLinkProperties(network)?.dnsServers?.forEach { address ->
                address.hostAddress?.let { servers += it }
            }
        }
        if (servers.isEmpty()) servers += listOf("1.1.1.1", "8.8.8.8")
        return servers
    }

    /** Corre el agente con la Secret Key ya guardada; bloquea mientras el proceso viva. */
    private suspend fun runAgent() {
        val proc = launch("--secret_path", secretFile.absolutePath, "start")
        process = proc
        proc.inputStream.bufferedReader().useLines { for (line in it) Unit }
        val code = proc.waitFor()
        process = null
        if (currentCoroutineContext().isActive) error("El agente playit terminó (código $code)")
    }

    private fun launch(vararg args: String): Process {
        val pb = ProcessBuilder(binary.absolutePath, "-s", *args)
            .directory(dir)
            .redirectErrorStream(true)
        pb.environment().putAll(
            mapOf(
                "HOME" to dir.absolutePath,
                "XDG_CONFIG_HOME" to dir.absolutePath,
                "TMPDIR" to File(context.cacheDir, "playit-tmp").apply { mkdirs() }.absolutePath,
            ),
        )
        return pb.start()
    }

    /** Crea el túnel una vez y refresca la dirección pública mientras el agente siga vivo. */
    private suspend fun pollTunnel(secret: String) {
        var created = false
        while (currentCoroutineContext().isActive) {
            val result = runCatching {
                val data = rundata(secret)
                findMinecraftAddress(data) ?: run {
                    if (!created) {
                        post("/tunnels/create", createTunnelBody(data.getString("agent_id")), secret)
                        created = true
                    }
                    null
                }
            }
            result.onFailure { failure ->
                if (_info.value.error == null) _info.value = _info.value.copy(error = friendly(failure))
            }
            val address = result.getOrNull()
            if (address != null && _info.value.address != address) {
                _info.value = _info.value.copy(address = address, error = null)
            }
            delay(if (address == null) 5_000 else 30_000)
        }
    }

    private fun findMinecraftAddress(data: JSONObject): String? {
        val tunnels = data.optJSONArray("tunnels") ?: return null
        for (i in 0 until tunnels.length()) {
            val tunnel = tunnels.optJSONObject(i) ?: continue
            val type = tunnel.optString("tunnel_type")
            val display = tunnel.optString("tunnel_type_display")
            if (type != "minecraft-java" && !display.contains("Minecraft", ignoreCase = true)) continue
            val address = tunnel.optString("assigned_domain").takeIf { it.isNotEmpty() }
                ?: tunnel.optString("display_address").takeIf { it.isNotEmpty() }
            if (address != null) return address
        }
        return null
    }

    private fun createTunnelBody(agentId: String): String = JSONObject().apply {
        put("name", "NomadServer")
        put("tunnel_type", "minecraft-java")
        put("port_type", "tcp")
        put("port_count", 1)
        put(
            "origin",
            JSONObject().apply {
                put("type", "agent")
                put(
                    "data",
                    JSONObject().apply {
                        put("agent_id", agentId)
                        put("local_ip", "127.0.0.1")
                        put("local_port", MC_PORT)
                    },
                )
            },
        )
        put("enabled", true)
        put("alloc", JSONObject.NULL)
        put("firewall_id", JSONObject.NULL)
        put("proxy_protocol", JSONObject.NULL)
    }.toString()

    /** `/agents/rundata` (legacy) con respaldo en `/v1/agents/rundata`. */
    private fun rundata(secret: String): JSONObject =
        runCatching { post("/agents/rundata", "{}", secret) }
            .getOrElse { post("/v1/agents/rundata", "{}", secret) }

    /** POST a la API de playit; el cuerpo viene envuelto en `{status, data}`. */
    private fun post(path: String, body: String, secret: String?): JSONObject {
        val json = call(path, body, secret)
        if (json.optString("status") != "success") {
            error("playit rechazó la petición: ${json.opt("data")}")
        }
        return json.getJSONObject("data")
    }

    /** Llamada cruda: devuelve el sobre `{status, data}` y falla solo si el HTTP no es 2xx. */
    private fun call(path: String, body: String, secret: String?): JSONObject {
        val conn = (URL("$API$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            // El agente oficial usa exactamente `Agent-Key <secret>` (ver api_client/src/lib.rs).
            if (secret != null) setRequestProperty("Authorization", "Agent-Key $secret")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) error("playit respondió $code: $text")
        return JSONObject(text)
    }

    private fun readSecret(): String? =
        secretFile.takeIf { it.exists() }?.readText()?.let { parseSecret(it) }

    private fun download(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        if (conn.responseCode !in 200..299) error("No se pudo descargar el agente playit (${conn.responseCode})")
        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
    }

    private fun randomCode(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun messageOf(t: Throwable): String = friendly(t) ?: t.message ?: t.javaClass.simpleName

    private companion object {
        const val VERSION = "0.17.1"
        const val BINARY = "playit"
        const val MC_PORT = 25565
        const val API = "https://api.playit.gg"
        const val SELF_MANAGED = "self-managed"
        const val AGENT_VERSION = "playit $VERSION"
        const val AGENT_URL =
            "https://github.com/playit-cloud/playit-agent/releases/download/v$VERSION/playit-linux-aarch64"

        /** Tiempo que se espera a que el usuario apruebe el agente en el navegador. */
        const val CLAIM_TIMEOUT_MS = 10 * 60 * 1000L
    }
}

/** La Secret Key del agente es hex. */
internal fun isValidSecret(text: String): Boolean = Regex("[0-9a-fA-F]{16,}").matches(text.trim())

/** `playit.toml` guarda `secret_key = "…"`; también se acepta el hex suelto. */
internal fun parseSecret(text: String): String? {
    Regex("""secret_key\s*=\s*"([0-9a-fA-F]+)"""").find(text)?.groupValues?.get(1)?.let { return it }
    return Regex("[0-9a-fA-F]{16,}").find(text.trim())?.value
}

/** Traduce los errores de la API a algo legible para el usuario. */
internal fun friendly(error: Throwable?): String? {
    val message = error?.message ?: return null
    return when {
        message.contains("InvalidAgentKey") -> "La Secret Key no es válida"
        message.contains("EmailMustBeVerified") -> "Verifica tu correo en playit.gg para crear el túnel"
        message.contains("GuestAccountNotAllowed") -> "Esa cuenta no puede crear túneles; usa una cuenta normal"
        message.contains("AgentNotFound") || message.contains("InvalidAgentId") -> "El agente no existe en tu cuenta"
        message.contains("CodeExpired") -> "El enlace de aprobación expiró; vuelve a intentarlo"
        message.contains("UserRejected") -> "Rechazaste el agente en playit.gg"
        message.contains("AuthRequired") -> "Inicia sesión en playit.gg para aprobar el agente"
        message.contains("Could not resolve") || message.contains("Unable to resolve host") ||
            message.contains("UnknownHost") -> "Sin conexión con playit.gg (DNS)"
        else -> message
    }
}

/**
 * El agente (musl estático) abre `/etc/resolv.conf`, inexistente en Android. Se cambia ese literal
 * por `dns.conf` (relativo al cwd) conservando el tamaño: musl lo busca por NUL, así que vale con
 * una cadena más corta dentro del hueco original.
 */
internal fun patchResolvPath(bytes: ByteArray): ByteArray {
    val needle = "/etc/resolv.conf\u0000".toByteArray(Charsets.US_ASCII)
    val at = indexOf(bytes, needle)
    require(at >= 0) { "El agente de playit no tiene el literal /etc/resolv.conf esperado" }
    Arrays.fill(bytes, at, at + needle.size, 0)
    val replacement = "dns.conf\u0000".toByteArray(Charsets.US_ASCII)
    System.arraycopy(replacement, 0, bytes, at, replacement.size)
    return bytes
}

private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || haystack.size < needle.size) return -1
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}
