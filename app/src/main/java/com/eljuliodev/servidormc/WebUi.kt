package com.eljuliodev.servidormc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.StatFs
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

    private data class PlayersInfo(
        val ops: List<String>,
        val whitelist: List<String>,
        val bannedIps: List<String>,
        val bannedPlayers: List<String>,
    )

    /** `ops.json` / `whitelist.json` por servidor; se invalidan al abrir y tras cada acción. */
    private val playersCache = mutableMapOf<String, PlayersInfo>()

    /** Tamaños de las carpetas del mundo por servidor (se calculan a demanda). */
    private val worldCache = mutableMapOf<String, JSONObject>()

    /** Último listado de archivos por servidor (navegación a demanda). */
    private val filesCache = mutableMapOf<String, JSONObject>()

    /** Vista previa del optimizador por servidor (a demanda). */
    private val optimizeCache = mutableMapOf<String, JSONObject>()

    /** `.zip` de mundo elegido para [importWorld]; el picker se lanza desde el bridge. */
    private var pendingWorldImport: String? = null
    private val pickWorldZip = (activity as? ComponentActivity)?.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val id = pendingWorldImport
        pendingWorldImport = null
        if (id != null && uri != null) importWorldZip(id, uri)
    }

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

    /** La web avisó con `ready()`. */
    private var webReady = false

    /** Servidor que pidió abrir la notificación; se aplica en cuanto la web está lista. */
    private var pendingOpen: String? = null

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
        // El túnel público es global: cualquier cambio de estado se refleja en el snapshot.
        scope.launch { app.playit.info.collect { schedulePush() } }
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
        // La RAM sólo se sondea con la UI visible (ver ServerManager.monitorRam).
        app.setUiVisible(true)
        view.onResume()
        schedulePush()
    }

    fun onPause() {
        resumed = false
        app.setUiVisible(false)
        view.onPause()
    }

    /**
     * Abre el panel del servidor indicado en el intent (lo usa la notificación). Si la web aún
     * no cargó, queda pendiente hasta su `ready()`.
     */
    fun openFromIntent(intent: Intent?) {
        val id = intent?.getStringExtra(MainActivity.EXTRA_SERVER_ID) ?: return
        pendingOpen = id
        applyPendingOpen()
    }

    private fun applyPendingOpen() {
        if (!webReady) return
        val id = pendingOpen ?: return
        pendingOpen = null
        view.evaluateJavascript(
            "window.nomadOpenServer && window.nomadOpenServer(${JSONObject.quote(id)})",
            null,
        )
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
            webReady = true
            applyPendingOpen()
            schedulePush()
        }

        @JavascriptInterface
        fun openServer(id: String) = onMain {
            activeId = id
            playersCache.remove(id)
            worldCache.remove(id)
            filesCache.remove(id)
            optimizeCache.remove(id)
            observeActive()
            schedulePush()
        }

        @JavascriptInterface
        fun fetchVersions() = onMain {
            scope.launch {
                val list = withContext(Dispatchers.IO) {
                    runCatching {
                        ServerFiles.listVersions(File(activity.cacheDir, ServerFiles.MANIFEST_CACHE_NAME))
                    }.getOrDefault(emptyList())
                }
                versions = list
                schedulePush()
            }
        }

        /** Acciones sobre jugadores: van por consola, así que requieren el server encendido. */
        @JavascriptInterface
        fun playerAction(id: String, action: String, name: String, reason: String) = onMain {
            val command = when (action) {
                "op" -> "op $name"
                "deop" -> "deop $name"
                "kick" -> "kick $name"
                "ban" -> if (reason.isBlank()) "ban $name" else "ban $name $reason"
                "pardon" -> "pardon $name"
                "whitelistAdd" -> "whitelist add $name"
                "whitelistRemove" -> "whitelist remove $name"
                "banIp" -> "ban-ip $name"
                "pardonIp" -> "pardon-ip $name"
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
                worldCache.remove(id)
                filesCache.remove(id)
                optimizeCache.remove(id)
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

        /** Cambia la versión del perfil; el `server.jar` se descarga al próximo arranque. */
        @JavascriptInterface
        fun setVersion(id: String, version: String) = onMain {
            val manager = app.managerFor(id)
            if (manager.status.value == ServerManager.Status.Running ||
                manager.status.value == ServerManager.Status.Starting
            ) {
                toast("Detén el servidor para cambiar la versión")
                return@onMain
            }
            scope.launch {
                withContext(Dispatchers.IO) {
                    ServerProfileStore.setVersion(activity, id, version.ifEmpty { null })
                }
                versionCache.remove(id)
                reload()
                schedulePush()
            }
        }

        @JavascriptInterface
        fun requestSeed(id: String) = onMain { app.managerFor(id).sendCommand("seed") }

        /** Calcula tamaños/espacio del servidor en segundo plano y los deja en el snapshot. */
        @JavascriptInterface
        fun worldInfo(id: String) = onMain {
            scope.launch {
                val info = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(activity.filesDir, "servers/$id")
                        WorldTools.sizes(dir, freeBytes(), deviceTotalBytes())
                            .toJson()
                            // Como string: la semilla es un long de 64 bits y JS perdería precisión.
                            .put("seed", WorldTools.seed(dir)?.toString() ?: JSONObject.NULL)
                    }.getOrNull()
                }
                if (info != null) worldCache[id] = info
                schedulePush()
            }
        }

        /** Cuánto se puede liberar, sin tocar nada (vista previa del optimizador). */
        @JavascriptInterface
        fun optimizePreview(id: String, mode: String) = onMain {
            scope.launch {
                val preview = withContext(Dispatchers.IO) {
                    runCatching {
                        RegionOptimizer.preview(File(activity.filesDir, "servers/$id"), optimizeMode(mode))
                    }.getOrNull()
                }
                if (preview != null) optimizeCache[id] = preview.toJson()
                schedulePush()
            }
        }

        /** Aplica el optimizador elegido (`compact` | `remove`). Requiere server apagado. */
        @JavascriptInterface
        fun optimizeWorld(id: String, mode: String) = onMain {
            scope.launch {
                val manager = app.managerFor(id)
                val chosen = optimizeMode(mode)
                manager.note(if (chosen == RegionOptimizer.Mode.COMPACT) "Compactando regiones…" else "Quitando chunks sin visitar…")
                val result = withContext(Dispatchers.IO) {
                    runCatching { RegionOptimizer.optimize(File(activity.filesDir, "servers/$id"), chosen) }
                        .getOrNull()
                }
                optimizeCache.remove(id)
                worldCache.remove(id)
                filesCache.remove(id)
                manager.note(
                    result?.let { "Optimizado: ${humanBytes(it.reclaimable)} liberados (${it.unvisited} chunks, ${it.logFiles} logs)" }
                        ?: "No se pudo optimizar",
                )
                toast(result?.let { "Liberados ${humanBytes(it.reclaimable)}" } ?: "No se pudo optimizar")
                schedulePush()
            }
        }

        /** Lista un directorio del servidor (relativo a su carpeta); se entrega en `active.files`. */
        @JavascriptInterface
        fun listFiles(id: String, path: String) = onMain {
            scope.launch {
                val listing = withContext(Dispatchers.IO) {
                    runCatching {
                        val entries = FileBrowser.list(File(activity.filesDir, "servers/$id"), path)
                        JSONObject().apply {
                            put("path", path)
                            put(
                                "entries",
                                JSONArray().apply {
                                    entries.forEach { entry ->
                                        put(
                                            JSONObject().apply {
                                                put("name", entry.name)
                                                put("directory", entry.directory)
                                                put("size", entry.size)
                                                put("modified", entry.modified)
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }.getOrNull()
                }
                if (listing != null) filesCache[id] = listing
                schedulePush()
            }
        }

        @JavascriptInterface
        fun regenerateWorld(id: String, dimension: String) = onMain {
            scope.launch {
                val deleted = withContext(Dispatchers.IO) {
                    runCatching { WorldTools.regenerate(File(activity.filesDir, "servers/$id"), dimension) }
                        .getOrDefault(false)
                }
                worldCache.remove(id)
                if (!deleted) toast("No había nada que regenerar")
                schedulePush()
            }
        }

        @JavascriptInterface
        fun importWorld(id: String) = onMain {
            val launcher = pickWorldZip
            if (launcher == null) {
                toast("Selector de archivos no disponible")
                return@onMain
            }
            pendingWorldImport = id
            launcher.launch(arrayOf("*/*"))
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

        /** Túnel público playit.gg: claim con el navegador, linkear una key, encender y apagar. */
        @JavascriptInterface
        fun playitClaim() = onMain { app.playit.claim() }

        @JavascriptInterface
        fun playitLink(secret: String) = onMain { app.playit.link(secret) }

        @JavascriptInterface
        fun playitStart() = onMain { app.playit.start() }

        @JavascriptInterface
        fun playitStop() = onMain { app.playit.stop() }

        @JavascriptInterface
        fun playitUnlink() = onMain { app.playit.unlink() }

        /** Abre en el navegador el enlace de aprobación pendiente del claim. */
        @JavascriptInterface
        fun playitOpenClaim() = onMain {
            val url = app.playit.info.value.claimUrl ?: return@onMain
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { toast("No se pudo abrir el navegador") }
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
        activeJobs += scope.launch { manager.logSignal.collect { schedulePush() } }
        activeJobs += scope.launch { manager.ramUsedMb.collect { schedulePush() } }
        activeJobs += scope.launch { manager.cpuPercent.collect { schedulePush() } }
        activeJobs += scope.launch { manager.players.collect { schedulePush() } }
        activeJobs += scope.launch { manager.tps.collect { schedulePush() } }
        activeJobs += scope.launch { manager.seed.collect { schedulePush() } }
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
                    put("mcVersion", profile.mcVersion ?: JSONObject.NULL)
                    put("iconVersion", iconVersionOf(profile.id) ?: JSONObject.NULL)
                },
            )
        }

        val root = JSONObject().apply {
            put("servers", servers)
            put("playit", app.playit.info.value.toJson())
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
                    put("cpuPercent", manager.cpuPercent.value ?: JSONObject.NULL)
                    put("players", JSONArray(manager.players.value.toList()))
                    playersOf(id).let { info ->
                        put("ops", JSONArray(info.ops))
                        put("whitelist", JSONArray(info.whitelist))
                        put("bannedIps", JSONArray(info.bannedIps))
                        put("bannedPlayers", JSONArray(info.bannedPlayers))
                    }
                    put("seed", manager.seed.value ?: JSONObject.NULL)
                    put("world", worldCache[id] ?: JSONObject.NULL)
                    put("files", filesCache[id] ?: JSONObject.NULL)
                    put("optimize", optimizeCache[id] ?: JSONObject.NULL)
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
        val (window, total) = manager.logSnapshot()
        val delta = logDelta(
            sameServer = lastLogServer == id,
            logs = window,
            total = total,
            lastTotal = lastLogTotal,
        )
        target.put("logs", JSONArray(delta.lines))
        target.put("logsReset", delta.reset)
        // Número absoluto de líneas: la web lo usa como clave estable del log.
        target.put("logTotal", total)
        lastLogServer = id
        lastLogTotal = total
    }

    private fun settingsOf(id: String): ServerSettings =
        settingsCache.getOrPut(id) { ServerSettings.read(File(activity.filesDir, "servers/$id")) }

    private fun mcVersionOf(id: String): String? = profiles.find { it.id == id }?.mcVersion

    private fun playersOf(id: String): PlayersInfo = playersCache.getOrPut(id) {
        val dir = File(activity.filesDir, "servers/$id")
        PlayersInfo(
            ops = PlayersStore.ops(dir),
            whitelist = PlayersStore.whitelist(dir),
            bannedIps = PlayersStore.bannedIps(dir),
            bannedPlayers = PlayersStore.bannedPlayers(dir),
        )
    }

    private fun freeBytes(): Long =
        runCatching { StatFs(activity.filesDir.path).availableBytes }.getOrDefault(0L)

    private fun deviceTotalBytes(): Long =
        runCatching { StatFs(activity.filesDir.path).totalBytes }.getOrDefault(0L)

    private fun humanBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) "${value.toLong()} ${units[index]}" else "%.1f %s".format(value, units[index])
    }

    private fun optimizeMode(mode: String): RegionOptimizer.Mode =
        if (mode == RegionOptimizer.Mode.REMOVE_UNVISITED.id) RegionOptimizer.Mode.REMOVE_UNVISITED
        else RegionOptimizer.Mode.COMPACT

    private fun PlayitInfo.toJson(): JSONObject = JSONObject().apply {
        put("state", state.name)
        put("linked", linked)
        put("claimUrl", claimUrl ?: JSONObject.NULL)
        put("address", address ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
    }

    private fun RegionOptimizer.Preview.toJson(): JSONObject = JSONObject().apply {
        put("mode", mode)
        put("regionFiles", regionFiles)
        put("chunks", chunks)
        put("unvisited", unvisited)
        put("regionBefore", regionBefore)
        put("regionAfter", regionAfter)
        put("logFiles", logFiles)
        put("logBytes", logBytes)
        put("reclaimable", reclaimable)
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    /** Copia el `.zip` elegido y reemplaza el mundo; corre en IO y va contando en la consola. */
    private fun importWorldZip(id: String, uri: Uri) {
        val manager = app.managerFor(id)
        scope.launch {
            manager.note("Importando mundo…")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(activity.filesDir, "servers/$id")
                    val stream = activity.contentResolver.openInputStream(uri)
                        ?: error("No se pudo abrir el archivo")
                    stream.use { WorldTools.importZip(dir, it, freeBytes()).getOrThrow() }
                }
            }
            worldCache.remove(id)
            filesCache.remove(id)
            result
                .onSuccess { manager.note("Mundo importado") }
                .onFailure { manager.note("Importación fallida: ${it.message}") }
            toast(result.fold({ "Mundo importado" }, { "Importación fallida: ${it.message}" }))
            schedulePush()
        }
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
