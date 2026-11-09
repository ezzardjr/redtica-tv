package com.redtica.camarastv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Servicio en primer plano: escucha eventos de las cámaras, graba clips,
 * sube a Google Drive y atiende el panel web de configuración (puerto 8080).
 * Funciona aunque la pantalla de mosaico no esté abierta.
 */
class CameraService : Service() {
    private var recorder: Recorder? = null
    private var uploader: Uploader? = null
    private var web: WebPanel? = null
    private val monitors = ArrayList<EventMonitor>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val onConfig: () -> Unit = { restartMonitors() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        startAsForeground()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "camarastv:servicio").apply { acquire() }

        val up = Uploader(this).also { it.start() }
        uploader = up
        recorder = Recorder(this) { up.kick() }
        web = try { WebPanel(this, 8080).also { it.start() } } catch (e: Exception) { null }
        AppState.addConfigListener(onConfig)
        restartMonitors()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun restartMonitors() {
        monitors.forEach { it.shutdown() }
        monitors.clear()
        AppState.camStatus.clear()
        val cfg = AppState.config
        if (!cfg.recordEnabled) return
        cfg.cameras.forEachIndexed { i, cam ->
            if (cam.canEvents) {
                val m = EventMonitor(i, cam, cfg) { idx, type -> recorder?.record(idx, type) }
                monitors.add(m); m.start()
            }
        }
        AppState.changed()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("svc", "Cámaras TV", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "svc") else Notification.Builder(this)
        val n = b.setContentTitle("Cámaras TV activo")
            .setContentText("Detección de movimiento y respaldo en la nube")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        AppState.removeConfigListener(onConfig)
        monitors.forEach { it.shutdown() }
        uploader?.shutdown()
        recorder?.release()
        web?.stop()
        try { wakeLock?.release() } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, CameraService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try { CameraService.start(ctx) } catch (_: Exception) { }
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }
}
