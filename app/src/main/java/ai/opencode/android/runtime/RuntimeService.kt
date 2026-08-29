package ai.opencode.android.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.opencode.android.R

/**
 * Foreground service that owns the [RuntimeManager] supervisor.
 *
 * Why a foreground service: the embedded OpenCode server is long-running local
 * compute (agent turns, shell/git commands, stdio MCP servers continue after
 * the user leaves the chat screen). Without it, background/doze process
 * restrictions could kill the agent and its child processes mid-turn. The
 * notification is low-priority and stops with the runtime.
 *
 * Lifecycle: START_NOT_STICKY + explicit start. The app (and the on-device
 * gate flow) starts the service when the runtime is wanted; a user/CI stop
 * (RuntimeService.stop) tears the supervisor down and the service stops, so
 * the runtime stays down until the app is next opened. Restart-across-
 * process-death will be handled with the Phase 5 UI; the supervisor itself is
 * idempotent and re-attaches/re-validates on every start (it sweeps stale
 * servers via the pidfile/proc check, so nothing duplicates).
 */
class RuntimeService : Service() {

    private lateinit var manager: RuntimeManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = RuntimeManager.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent?.action == ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            manager.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        manager.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The ACTION_STOP path has already asked the manager to shut the
        // runtime down gracefully; nothing else to do (START_NOT_STICKY: the
        // runtime restarts only when the app next starts the service).
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "opencode-runtime"
        const val NOTIF_ID = 4204
        const val ACTION_STOP = "ai.opencode.android.STOP_RUNTIME"

        fun start(context: Context) {
            val intent = Intent(context, RuntimeService::class.java)
            context.startForegroundService(intent)
        }

        /**
         * Graceful, explicit stop. We send a start-with-stop action because a
         * service that was started with startForegroundService must go through
         * the service (it posts its FGS notification first, then asks the
         * supervisor to shut the runtime down and stops itself).
         */
        fun stop(context: Context) {
            val intent = Intent(context, RuntimeService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startForegroundService(intent) }
        }
    }
}
