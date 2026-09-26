package com.eljuliodev.servidormc

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/** `Starting`, `Running` y `Stopping` cuentan como "servidor activo" para el servicio. */
private val ServerManager.Status.active: Boolean
    get() = this == ServerManager.Status.Starting ||
        this == ServerManager.Status.Running ||
        this == ServerManager.Status.Stopping

class NomadApplication : Application() {

    private val managers = mutableMapOf<String, ServerManager>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Túnel público de playit.gg; es global (un agente para toda la app). */
    val playit: PlayitManager by lazy { PlayitManager(this) }

    /** Un ServerManager por perfil, creado la primera vez y reutilizado después. */
    fun managerFor(serverId: String): ServerManager =
        managers.getOrPut(serverId) {
            ServerManager(this, serverId).also { manager ->
                // Cada cambio de estado decide si el servicio en primer plano sigue o no.
                scope.launch { manager.status.collect { syncForegroundService() } }
            }
        }

    /** El servidor activo (arrancando/corriendo/deteniéndose), o null si no hay ninguno. */
    private fun activeServerId(): String? =
        managers.entries.firstOrNull { (_, manager) -> manager.status.value.active }?.key

    private fun syncForegroundService() {
        val active = activeServerId()
        if (active != null) ServerService.start(this, active) else ServerService.stop(this)
    }

    /** Avisa a todos los managers si la UI está visible (así el sondeo de RAM no corre de fondo). */
    fun setUiVisible(visible: Boolean) {
        managers.values.forEach { it.setUiVisible(visible) }
    }

    /** Nombre del perfil (para la notificación). */
    fun serverName(serverId: String): String =
        ServerProfileStore.list(this).firstOrNull { it.id == serverId }?.name ?: "Servidor"

    /** Icono 64x64 del servidor, o null si todavía no tiene. */
    fun serverIcon(serverId: String): Bitmap? = runCatching {
        BitmapFactory.decodeFile(File(filesDir, "servers/$serverId/server-icon.png").absolutePath)
    }.getOrNull()
}
