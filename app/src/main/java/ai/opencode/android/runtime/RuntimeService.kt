package ai.opencode.android.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.opencode.android.R
import ai.opencode.android.task.BackgroundTask
import ai.opencode.android.task.BackgroundTaskHub
import ai.opencode.android.task.BackgroundTaskNotice
import ai.opencode.android.task.BackgroundTaskReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the [RuntimeManager] supervisor.
 *
 * Why a foreground service: the embedded OpenCode server is long-running local
 * compute (agent turns, shell/git commands, stdio MCP servers continue after
 * the user leaves the chat screen). Without it, background/doze process
 * restrictions could kill the agent and its child processes mid-turn.
 *
 * The notification shows the app icon and the agent's state, with the taps that
 * state allows (see [RuntimeNoticeState]): Ready and Error offer Exit; Working
 * offers Pause and Exit; Paused offers Resume and Exit. Exit stops the runtime.
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
    private var explicitStopRequested = false
    private var scope: CoroutineScope? = null
    private var noticeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = RuntimeManager.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent?.action == ACTION_STOP) {
            explicitStopRequested = true
            noticeJob?.cancel()
            noticeJob = null
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
        explicitStopRequested = false
        observeNotice()
        manager.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // onDestroy can also follow a system/service teardown rather than the
        // explicit ACTION_STOP path. Always request cleanup in that case so a
        // child process cannot outlive the service unnoticed; RuntimeProcess
        // uses a synchronized start/stop boundary to cover an in-flight launch.
        noticeJob?.cancel()
        noticeJob = null
        scope?.cancel()
        scope = null
        if (::manager.isInitialized && !explicitStopRequested) manager.stop()
        super.onDestroy()
    }

    /** Keeps the notification in step with the runtime and the background task. */
    private fun observeNotice() {
        if (noticeJob != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        noticeJob = s.launch {
            combine(manager.state, taskStatus()) { runtime, task -> runtime.status to task }
                .distinctUntilChanged()
                .collect { (runtime, task) ->
                    if (!explicitStopRequested) postNotice(runtime, task)
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun taskStatus(): Flow<BackgroundTask.Status?> =
        BackgroundTaskHub.current.flatMapLatest { current ->
            if (current == null) {
                flowOf<BackgroundTask.Status?>(null)
            } else {
                current.control.status.map { it }
            }
        }

    private fun startInForeground() {
        val notification = buildNotice(
            manager.state.value.status,
            BackgroundTaskHub.current.value?.control?.status?.value,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun postNotice(runtime: RuntimeStatus, task: BackgroundTask.Status?) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotice(runtime, task))
    }

    private fun buildNotice(runtime: RuntimeStatus, task: BackgroundTask.Status?): Notification {
        val kind = RuntimeNoticeState.kindOf(runtime, task)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setLargeIcon(appIcon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(statusLabelRes(kind)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        for (tap in RuntimeNoticeState.tapsOf(kind)) {
            builder.addAction(0, getString(tapLabelRes(tap)), pendingFor(tap))
        }
        return builder.build()
    }

    private fun statusLabelRes(kind: RuntimeNoticeState.Kind): Int = when (kind) {
        RuntimeNoticeState.Kind.STARTING -> R.string.notification_status_starting
        RuntimeNoticeState.Kind.READY -> R.string.notification_status_ready
        RuntimeNoticeState.Kind.WORKING -> R.string.notification_status_working
        RuntimeNoticeState.Kind.PAUSED -> R.string.notification_status_paused
        RuntimeNoticeState.Kind.ERROR -> R.string.notification_status_error
    }

    private fun tapLabelRes(tap: RuntimeNoticeState.Tap): Int = when (tap) {
        RuntimeNoticeState.Tap.PAUSE -> R.string.task_action_pause
        RuntimeNoticeState.Tap.RESUME -> R.string.task_action_resume
        RuntimeNoticeState.Tap.EXIT -> R.string.notification_action_exit
    }

    /** Exit is a service action; Pause and Resume go to the background-task receiver. */
    private fun pendingFor(tap: RuntimeNoticeState.Tap): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return when (tap) {
            RuntimeNoticeState.Tap.EXIT -> PendingIntent.getService(
                this,
                tap.ordinal,
                Intent(this, RuntimeService::class.java).setAction(ACTION_STOP),
                flags,
            )
            RuntimeNoticeState.Tap.PAUSE -> PendingIntent.getBroadcast(
                this,
                tap.ordinal,
                Intent(this, BackgroundTaskReceiver::class.java)
                    .setAction(BackgroundTaskNotice.actionOf(BackgroundTask.Command.PAUSE)),
                flags,
            )
            RuntimeNoticeState.Tap.RESUME -> PendingIntent.getBroadcast(
                this,
                tap.ordinal,
                Intent(this, BackgroundTaskReceiver::class.java)
                    .setAction(BackgroundTaskNotice.actionOf(BackgroundTask.Command.RESUME)),
                flags,
            )
        }
    }

    /** The app icon as the large icon. Drawn once; falls back to no large icon. */
    private val appIcon: Bitmap? by lazy {
        runCatching {
            val drawable = packageManager.getApplicationIcon(packageName)
            val size = (resources.displayMetrics.density * 48).toInt().coerceAtLeast(48)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            bitmap
        }.getOrNull()
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
