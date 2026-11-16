package ai.opencode.android.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the taps on the background-task notification (Pause, Resume, Cancel,
 * Dismiss). Not exported: only this app's own PendingIntents reach it.
 */
class BackgroundTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val command = BackgroundTaskNotice.commandOf(intent?.action) ?: return
        BackgroundTaskHub.perform(command)
    }
}
