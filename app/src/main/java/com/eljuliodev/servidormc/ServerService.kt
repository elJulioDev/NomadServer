package com.eljuliodev.servidormc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano: mantiene viva la app (y con ella el proceso del servidor) en segundo
 * plano con una notificación fija. La notificación muestra el icono, el nombre del servidor y los
 * jugadores, y trae la acción "Detener". No se puede descartar deslizándola; desaparece sola
 * cuando el servidor se apaga.
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var serverId: String? = null
    private var serverName: String = "Servidor"
    private var serverIcon: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVER) {
            serverId?.let { (application as NomadApplication).managerFor(it).stop() }
            return START_NOT_STICKY
        }

        val id = intent?.getStringExtra(EXTRA_SERVER_ID)
        if (id == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val app = application as NomadApplication
        serverId = id
        serverName = app.serverName(id)
        serverIcon = app.serverIcon(id)
        currentId = id
        startForeground(NOTIFICATION_ID, buildNotification())
        observe(app, id)
        return START_NOT_STICKY
    }

    private fun observe(app: NomadApplication, id: String) {
        observeJob?.cancel()
        val manager = app.managerFor(id)
        observeJob = scope.launch {
            launch { manager.status.collect { refresh() } }
            launch { manager.players.collect { refresh() } }
        }
    }

    /** Reconstruye la notificación; si el servidor ya no está activo, cierra el servicio. */
    private fun refresh() {
        val id = serverId ?: return
        val status = (application as NomadApplication).managerFor(id).status.value
        if (status == ServerManager.Status.Stopped || status == ServerManager.Status.Error) {
            stopSelf()
            return
        }
        notificationManager().notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val id = serverId ?: return buildPlaceholder()
        val manager = (application as NomadApplication).managerFor(id)
        val players = manager.players.value.size
        val text = when (manager.status.value) {
            ServerManager.Status.Starting -> "Arrancando…"
            ServerManager.Status.Stopping -> "Deteniendo…"
            else -> when (players) {
                0 -> "Sin jugadores conectados"
                1 -> "1 jugador conectado"
                else -> "$players jugadores conectados"
            }
        }

        val openIntent = PendingIntent.getActivity(
            this,
            id.hashCode(),
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SERVER_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            id.hashCode() xor 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP_SERVER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .apply { serverIcon?.let { setLargeIcon(it) } }
            .setContentTitle(serverName)
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop_notification),
                    "Detener",
                    stopIntent,
                ).build(),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun buildPlaceholder(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_notification)
            .setContentTitle(serverName)
            .setOngoing(true)
            .build()

    override fun onCreate() {
        super.onCreate()
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Servidor en ejecución", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Muestra el servidor que está corriendo y permite detenerlo."
                setShowBadge(false)
            },
        )
    }

    override fun onDestroy() {
        currentId = null
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "server_running"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_SERVER = "com.eljuliodev.servidormc.action.STOP_SERVER"
        private const val EXTRA_SERVER_ID = "serverId"

        /** Id que muestra la notificación (null si el servicio no corre). Vive en el proceso. */
        @Volatile
        private var currentId: String? = null

        /** Arranca o cambia el servicio para [id]; si ya muestra ese servidor, no hace nada. */
        fun start(context: Context, id: String) {
            if (currentId == id) return
            val intent = Intent(context, ServerService::class.java).putExtra(EXTRA_SERVER_ID, id)
            // Puede fallar si el sistema no permite arrancar un FGS desde el fondo; se ignora.
            runCatching { context.startForegroundService(intent) }
        }

        /** Detiene el servicio (y con él la notificación). */
        fun stop(context: Context) {
            if (currentId == null) return
            currentId = null
            context.stopService(Intent(context, ServerService::class.java))
        }
    }
}
