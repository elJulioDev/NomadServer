package com.eljuliodev.servidormc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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

    /** Mientras la Activity no está visible no se serializa ni se empuja nada. */
    private var resumed = false

    init {
        @SuppressLint("SetJavaScriptEnabled")
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity))
            .build()
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
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
            observeActive()
            schedulePush()
        }

        @JavascriptInterface
        fun closeServer() = onMain {
            activeId = null
            observeActive()
            schedulePush()
        }

        @JavascriptInterface
        fun createServer(name: String, ramMb: Int, maxPlayers: Int) = onMain {
            scope.launch {
                withContext(Dispatchers.IO) {
                    ServerProfileStore.add(activity, name, ramMb, maxPlayers)
                }
                reload()
                schedulePush()
            }
        }

        @JavascriptInterface
        fun deleteServer(id: String) = onMain {
            scope.launch {
                withContext(Dispatchers.IO) { ServerProfileStore.remove(activity, id) }
                if (activeId == id) activeId = null
                reload()
                observeActive()
                schedulePush()
            }
        }

        @JavascriptInterface
        fun startServer(id: String, ramMb: Int, maxPlayers: Int) = onMain {
            app.managerFor(id).start(ramMb, maxPlayers)
        }

        @JavascriptInterface
        fun stopServer(id: String) = onMain { app.managerFor(id).stop() }

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
                },
            )
        }

        val root = JSONObject().apply { put("servers", servers) }
        val id = activeId
        if (id == null) {
            lastLogServer = null
            root.put("active", JSONObject.NULL)
            root.put("lanAddress", JSONObject.NULL)
        } else {
            val manager = app.managerFor(id)
            val running = manager.status.value == ServerManager.Status.Running
            root.put(
                "active",
                JSONObject().apply {
                    put("id", id)
                    put("status", manager.status.value.name)
                    put("ramUsedMb", manager.ramUsedMb.value ?: JSONObject.NULL)
                    put("players", JSONArray(manager.players.value.toList()))
                    putActiveLogs(this, id, manager)
                },
            )
            root.put("lanAddress", if (running) lanAddress() ?: JSONObject.NULL else JSONObject.NULL)
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
