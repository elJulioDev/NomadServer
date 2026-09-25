package com.eljuliodev.servidormc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Hospeda la UI web (`app/src/main/assets/ui`, construida desde `web/`) y la conecta con el
 * estado Kotlin.
 *
 * Los assets se sirven por `https://appassets.androidplatform.net/` con [WebViewAssetLoader], no
 * por `file://`: Vite emite módulos ES y `file://` los bloquea por CORS (pantalla en blanco).
 *
 * Contrato con JavaScript:
 *  - JS llama a `window.NomadBridge.*` (ver [Bridge]) para pedir acciones.
 *  - Kotlin empuja el estado a `window.onNomadState(snapshot)`.
 *
 * El snapshot es deliberadamente "todo de una": la lista de servidores, el servidor activo
 * (status/ram/logs/players) y la IP LAN. JS sólo renderiza.
 */
class WebUi(private val activity: Activity, private val app: NomadApplication) {

    val view: WebView = WebView(activity)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var profiles: List<ServerProfile> = ServerProfileStore.list(activity)
    private var activeId: String? = null
    private val listJobs = mutableListOf<Job>()
    private val activeJobs = mutableListOf<Job>()

    // ponytail: push con throttle de 250 ms. Del log sólo viaja el delta desde el push anterior
    // (no las 2000 líneas enteras); el cliente reemplaza la lista cuando `logsReset` es true.
    private var pushPending = false
    private var lastPushAt = 0L
    private var lastLogServer: String? = null
    private var lastLogTotal = 0L

    private var lanCache: Pair<Long, String?>? = null
    private val versionCache = mutableMapOf<String, Pair<Long, String?>>()

    /** Ajustes por servidor; se leen una vez y se refrescan al guardarlos. */
    private val settingsCache = mutableMapOf<String, ServerSettings>()

    /** Versiones del manifest traídas por `fetchVersions` (null = todavía no pedidas). */
    private var versions: List<McVersion>? = null

    private data class PlayersInfo(val ops: List<String>, val whitelist: List<String>)

    /** `ops.json` / `whitelist.json` por servidor; se invalidan al abrir y tras cada acción. */
    private val playersCache = mutableMapOf<String, PlayersInfo>()

    /** Selección de imagen para `<input type="file">` (icono del servidor). */
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickImage = (activity as? ComponentActivity)?.registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val callback = fileCallback
        fileCallback = null
        callback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

    /** Mientras la Activity no está visible no se serializa ni se empuja nada. */
    private var resumed = false

    init {
        @SuppressLint("SetJavaScriptEnabled")
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            // Necesario para que `<input type="file">` pueda leer lo que devuelve el picker.
            allowContentAccess = true
        }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
            .addPathHandler(
                "/server-icons/",
                object : WebViewAssetLoader.PathHandler {
                    override fun handle(path: String): WebResourceResponse? {
                        val id = path.substringBefore('/')
                        if (!id.matches(ID_PATTERN)) return null
                        val file = File(activity.filesDir, "servers/$id/server-icon.png")
                        if (!file.exists()) return null
                        return WebResourceResponse("image/png", null, file.inputStream())
                    }
                },
            )
            .build()
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        }
        // `<input type="file">` para el icono del servidor; sin esto el picker no abre.
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val launcher = pickImage
                if (launcher == null) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    return true
                }
                launcher.launch("image/*")
                return true
            }
        }
        // Chrome DevTools (chrome://inspect) sólo en debug, para el flujo de diseño web.
        WebView.setWebContentsDebuggingEnabled(
            (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
        view.setBackgroundColor(BACKGROUND)
        view.addJavascriptInterface(Bridge(), "NomadBridge")
        view.loadUrl("https://appassets.androidplatform.net/assets/ui/index.html")
        observeList()
    }

    fun dispose() {
        scope.cancel()
        view.destroy()
    }

    /**
     * Con la app en segundo plano el server sigue, pero la UI no: no se construye JSON ni se
     * empuja nada. Al volver, el delta acumulado (o un reset si se perdió la ventana) se envía
     * de una sola vez.
     */
    fun onResume() {
        resumed = true
        view.onResume()
        schedulePush()
    }

    fun onPause() {
        resumed = false
        view.onPause()
    }

    /** Llama a `window.nomadHandleBack()`; [onResult] recibe true si la web consumió el "atrás". */
    fun handleBack(onResult: (Boolean) -> Unit) {
        view.evaluateJavascript("window.nomadHandleBack ? window.nomadHandleBack() : false") { result ->
            onResult(result == "true")
        }
    }

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }

    /** Punto de entrada de JavaScript; los métodos corren en un hilo Binder, no en el de UI. */
    private inner class Bridge {

        @JavascriptInterface
        fun ready() = onMain {
            // La web se acaba de cargar (o recargar): fuerza un snapshot completo, no un delta.
            lastLogServer = null
            schedulePush()
        }

        @JavascriptInterface
        fun openServer(id: String) = onMain {
            activeId = id
            playersCache.remove(id)
            observeActive()
            schedulePush()
        }

        @JavascriptInterface
        fun fetchVersions() = onMain {
            scope.launch {
                val list = withContext(Dispatchers.IO) {
                    runCatching { ServerFiles.listVersions() }.getOrDefault(emptyList())
                }
                versions = list
                schedulePush()
            }
        }

        /** Acciones sobre jugadores: van por consola, así que requieren el server encendido. */
        @JavascriptInterface
        fun playerAction(id: String, action: String, name: String) = onMain {
            val command = when (action) {
                "op" -> "op $name"
                "deop" -> "deop $name"
                "kick" -> "kick $name"
                "ban" -> "ban $name"
                "pardon" -> "pardon $name"
                "whitelistAdd" -> "whitelist add $name"
                "whitelistRemove" -> "whitelist remove $name"
                else -> null
            } ?: return@onMain
            app.managerFor(id).sendCommand(command)
            // Minecraft escribe los JSON al procesar el comando: refrescar con un margen.
            scope.launch {
                delay(600)
                playersCache.remove(id)
                schedulePush()
            }
        }

        @JavascriptInterface
        fun setWhitelistEnabled(id: String, enabled: Boolean) = onMain {
            val settings = settingsOf(id).copy(whitelist = enabled)
            settingsCache[id] = settings
            app.managerFor(id).sendCommand(if (enabled) "whitelist on" else "whitelist off")
            scope.launch {
                withContext(Dispatchers.IO) {
                    ServerSettings.write(File(activity.filesDir, "servers/$id"), settings)
                }
                schedulePush()
            }
        }

        @JavascriptInterface
        fun closeServer() = onMain {
            activeId = null
            observeActive()
            schedulePush()
        }

        @JavascriptInterface
        fun createServer(
            name: String,
            ramMb: Int,
            maxPlayers: Int,
            settingsJson: String,
            iconDataUrl: String,
            mcVersion: String,
        ) = onMain {
            val settings = settingsJson
                .takeIf { it.isNotEmpty() }
                ?.let { json -> runCatching { ServerSettings.fromJson(JSONObject(json)) }.getOrNull() }
            scope.launch {
                val profile = withContext(Dispatchers.IO) {
                    val created = ServerProfileStore.add(
                        activity,
                        name,
                        ramMb,
                        maxPlayers,
                        mcVersion.ifEmpty { null },
                    )
                    val dir = File(activity.filesDir, "servers/${created.id}")
                    if (iconDataUrl.isNotEmpty()) {
                        runCatching { ServerSettings.saveIcon(dir, iconDataUrl) }
                    }
                    if (settings != null) {
                        runCatching { ServerSettings.write(dir, settings) }
                    }
                    created
                }
                if (settings != null) settingsCache[profile.id] = settings
                reload()
                schedulePush()
            }
        }

        @JavascriptInterface
        fun deleteServer(id: String) = onMain {
            scope.launch {
                withContext(Dispatchers.IO) { ServerProfileStore.remove(activity, id) }
                settingsCache.remove(id)
                playersCache.remove(id)
                if (activeId == id) activeId = null
                reload()
                observeActive()
                schedulePush()
            }
        }

        @JavascriptInterface
        fun updateSettings(id: String, json: String) = onMain {
            val settings = runCatching { ServerSettings.fromJson(JSONObject(json)) }.getOrNull() ?: return@onMain
            scope.launch {
                withContext(Dispatchers.IO) {
                    ServerSettings.write(File(activity.filesDir, "servers/$id"), settings)
                    ServerProfileStore.setMaxPlayers(activity, id, settings.maxPlayers)
                }
                settingsCache[id] = settings
                reload()
                schedulePush()
            }
        }

        @JavascriptInterface
        fun setServerIcon(id: String, dataUrl: String) = onMain {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { ServerSettings.saveIcon(File(activity.filesDir, "servers/$id"), dataUrl) }
                        .getOrDefault(false)
                }
                if (!ok) Toast.makeText(activity, "No se pudo leer la imagen", Toast.LENGTH_SHORT).show()
                schedulePush()
            }
        }

        @JavascriptInterface
        fun startServer(id: String, ramMb: Int, maxPlayers: Int) = onMain {
            scope.launch {
                withContext(Dispatchers.IO) { ServerProfileStore.setRamMb(activity, id, ramMb) }
                app.managerFor(id).start(ramMb, maxPlayers, mcVersionOf(id))
                reload()
                schedulePush()
            }
        }

        @JavascriptInterface
        fun stopServer(id: String) = onMain { app.managerFor(id).stop() }

        @JavascriptInterface
        fun sendCommand(id: String, text: String) = onMain { app.managerFor(id).sendCommand(text) }

        @JavascriptInterface
        fun restartServer(id: String, ramMb: Int, maxPlayers: Int) = onMain {
            app.managerFor(id).restart(ramMb, maxPlayers, mcVersionOf(id))
        }

        @JavascriptInterface
        fun extendStartTimer(id: String) = onMain { app.managerFor(id).extendAutoStop() }

        @JavascriptInterface
        fun copy(text: String) = onMain {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("NomadServer", text))
            Toast.makeText(activity, "Copiado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reload() {
        profiles = ServerProfileStore.list(activity)
        observeList()
    }

    private fun observeList() {
        listJobs.forEach { it.cancel() }
        listJobs.clear()
        profiles.forEach { profile ->
            val manager = app.managerFor(profile.id)
            listJobs += scope.launch { manager.status.collect { schedulePush() } }
            listJobs += scope.launch { manager.players.collect { schedulePush() } }
        }
    }

    private fun observeActive() {
        activeJobs.forEach { it.cancel() }
        activeJobs.clear()
        val manager = activeId?.let(app::managerFor) ?: return
        activeJobs += scope.launch { manager.status.collect { schedulePush() } }
        activeJobs += scope.launch { manager.logs.collect { schedulePush() } }
        activeJobs += scope.launch { manager.ramUsedMb.collect { schedulePush() } }
        activeJobs += scope.launch { manager.players.collect { schedulePush() } }
        activeJobs += scope.launch { manager.tps.collect { schedulePush() } }
        activeJobs += scope.launch { manager.startProgress.collect { schedulePush() } }
        activeJobs += scope.launch { manager.autoStopSeconds.collect { schedulePush() } }
    }

    private fun schedulePush() {
        if (!resumed || pushPending) return
        val wait = (PUSH_INTERVAL_MS - (SystemClock.uptimeMillis() - lastPushAt)).coerceAtLeast(0)
        pushPending = true
        scope.launch {
            delay(wait)
            pushPending = false
            lastPushAt = SystemClock.uptimeMillis()
            push()
        }
    }

    private fun push() {
        val payload = JSONObject.quote(snapshot())
        view.evaluateJavascript("window.onNomadState && window.onNomadState($payload)", null)
    }

    private fun snapshot(): String {
        val servers = JSONArray()
        profiles.forEach { profile ->
            val manager = app.managerFor(profile.id)
            servers.put(
                JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("ramMb", profile.ramMb)
                    put("maxPlayers", profile.maxPlayers)
                    put("status", manager.status.value.name)
                    put("players", JSONArray(manager.players.value.toList()))
                    put("version", versionOf(profile.id) ?: JSONObject.NULL)
                    put("iconVersion", iconVersionOf(profile.id) ?: JSONObject.NULL)
                },
            )
        }

        val root = JSONObject().apply {
            put("servers", servers)
            versions?.let { list ->
                put(
                    "versions",
                    JSONArray().apply {
                        list.forEach { version ->
                            put(
                                JSONObject().apply {
                                    put("id", version.id)
                                    put("type", version.type)
                                    put("releaseTime", version.releaseTime)
                                },
                            )
                        }
                    },
                )
            }
        }
        val id = activeId
        if (id == null) {
            lastLogServer = null
            root.put("active", JSONObject.NULL)
            root.put("lanAddress", JSONObject.NULL)
        } else {
            val manager = app.managerFor(id)
            root.put(
                "active",
                JSONObject().apply {
                    put("id", id)
                    put("status", manager.status.value.name)
                    put("ramUsedMb", manager.ramUsedMb.value ?: JSONObject.NULL)
                    put("players", JSONArray(manager.players.value.toList()))
                    playersOf(id).let { info ->
                        put("ops", JSONArray(info.ops))
                        put("whitelist", JSONArray(info.whitelist))
                    }
                    put("tps", manager.tps.value ?: JSONObject.NULL)
                    put("startProgress", manager.startProgress.value)
                    put("autoStopSeconds", manager.autoStopSeconds.value ?: JSONObject.NULL)
                    put("settings", settingsOf(id).toJson())
                    putActiveLogs(this, id, manager)
                },
            )
            root.put("lanAddress", lanAddress() ?: JSONObject.NULL)
        }
        return root.toString()
    }

    /**
     * Manda sólo las líneas nuevas. `logTotal` es acumulativo y la lista es una ventana de 2000,
     * así que si se escribieron más líneas de las que caben, hay que reenviar el log completo
     * (`logsReset`).
     */
    private fun putActiveLogs(target: JSONObject, id: String, manager: ServerManager) {
        val delta = logDelta(
            sameServer = lastLogServer == id,
            logs = manager.logs.value,
            total = manager.logTotal,
            lastTotal = lastLogTotal,
        )
        target.put("logs", JSONArray(delta.lines))
        target.put("logsReset", delta.reset)
        lastLogServer = id
        lastLogTotal = manager.logTotal
    }

    private fun settingsOf(id: String): ServerSettings =
        settingsCache.getOrPut(id) { ServerSettings.read(File(activity.filesDir, "servers/$id")) }

    private fun mcVersionOf(id: String): String? = profiles.find { it.id == id }?.mcVersion

    private fun playersOf(id: String): PlayersInfo = playersCache.getOrPut(id) {
        val dir = File(activity.filesDir, "servers/$id")
        PlayersInfo(PlayersStore.ops(dir), PlayersStore.whitelist(dir))
    }

    /** mtime del icono (0/null si no existe); sirve para invalidar la caché del `<img>` en JS. */
    private fun iconVersionOf(id: String): Long? {
        val file = File(activity.filesDir, "servers/$id/server-icon.png")
        return if (file.exists()) file.lastModified() else null
    }

    /** `version.txt` es I/O: se cachea, sólo cambia cuando se descarga el `server.jar`. */
    private fun versionOf(id: String): String? {
        val now = SystemClock.uptimeMillis()
        versionCache[id]?.let { (at, value) -> if (now - at < VERSION_CACHE_MS) return value }
        return ServerFiles.installedVersion(File(activity.filesDir, "servers/$id")).also {
            versionCache[id] = now to it
        }
    }

    private fun lanAddress(): String? {
        val now = SystemClock.uptimeMillis()
        lanCache?.let { (at, value) -> if (now - at < LAN_CACHE_MS) return value }
        return LanAddress.get().also { lanCache = now to it }
    }

    private companion object {
        const val PUSH_INTERVAL_MS = 250L
        const val LAN_CACHE_MS = 5_000L
        const val VERSION_CACHE_MS = 10_000L
        const val BACKGROUND = 0xFF0A0E17.toInt()
        val ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
    }
}

/**
 * Cuánto log enviar en este push. [sameServer] dice si [lastTotal] viene de este mismo servidor.
 *
 * `logTotal` es acumulativo aunque [logs] sea una ventana recortada a las últimas 2000 líneas,
 * así que el delta son las últimas `total - lastTotal`; si se escribieron más de las que caben en
 * la ventana (app en background un rato), no queda otra que reenviar todo ([LogDelta.reset]).
 */
internal data class LogDelta(val lines: List<String>, val reset: Boolean)

internal fun logDelta(sameServer: Boolean, logs: List<String>, total: Long, lastTotal: Long): LogDelta {
    val appended = if (sameServer) total - lastTotal else -1L
    val reset = appended < 0 || appended > logs.size
    return LogDelta(if (reset) logs else logs.takeLast(appended.toInt()), reset)
}
