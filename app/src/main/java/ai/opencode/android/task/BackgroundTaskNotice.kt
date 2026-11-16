package ai.opencode.android.task

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ai.opencode.android.R

/**
 * The notification for the current background task. An active task shows its
 * status with the taps for that state (Pause, Resume, Cancel). A finished task
 * shows its result and no taps; the notification clears when the bar is dismissed.
 *
 * Quiet by design: a low-importance channel, and nothing is posted when the user
 * has turned notifications off for the app.
 */
object BackgroundTaskNotice {
    const val CHANNEL_ID = "opencode-tasks"
    const val NOTIF_ID = 4205
    private const val ACTION_PREFIX = "ai.opencode.android.TASK_"

    fun actionOf(command: BackgroundTask.Command): String = ACTION_PREFIX + command.name

    /** The command a broadcast action stands for, or null for any other action. */
    fun commandOf(action: String?): BackgroundTask.Command? =
        BackgroundTask.Command.values().firstOrNull { actionOf(it) == action }

    fun labelRes(status: BackgroundTask.Status): Int = when (status) {
        BackgroundTask.Status.QUEUED -> R.string.task_status_queued
        BackgroundTask.Status.RUNNING -> R.string.task_status_running
        BackgroundTask.Status.PAUSED -> R.string.task_status_paused
        BackgroundTask.Status.DONE -> R.string.task_status_done
        BackgroundTask.Status.INCOMPLETE -> R.string.task_status_incomplete
        BackgroundTask.Status.TIMED_OUT -> R.string.task_status_timed_out
        BackgroundTask.Status.FAILED -> R.string.task_status_failed
        BackgroundTask.Status.CANCELLED -> R.string.task_status_cancelled
    }

    fun commandLabelRes(command: BackgroundTask.Command): Int = when (command) {
        BackgroundTask.Command.PAUSE -> R.string.task_action_pause
        BackgroundTask.Command.RESUME -> R.string.task_action_resume
        BackgroundTask.Command.CANCEL -> R.string.task_action_cancel
        BackgroundTask.Command.DISMISS -> R.string.task_action_dismiss
    }

    @SuppressLint("MissingPermission")
    fun post(context: Context, name: String, status: BackgroundTask.Status) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.task_title, name))
            .setContentText(context.getString(labelRes(status)))
            .setOngoing(status.active)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!status.active)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        for (command in status.commands()) {
            if (command == BackgroundTask.Command.DISMISS) continue
            builder.addAction(
                0,
                context.getString(commandLabelRes(command)),
                pendingFor(context, command),
            )
        }
        manager.notify(NOTIF_ID, builder.build())
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }

    private fun pendingFor(context: Context, command: BackgroundTask.Command): PendingIntent {
        val intent = Intent(context, BackgroundTaskReceiver::class.java).setAction(actionOf(command))
        return PendingIntent.getBroadcast(
            context,
            command.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.task_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}
