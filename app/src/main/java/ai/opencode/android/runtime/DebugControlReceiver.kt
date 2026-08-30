package ai.opencode.android.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Debug-only control surface for CI gates (no root required on debug builds):
 *
 *   adb shell am broadcast -a ai.opencode.android.DEBUG_STOP   <pkg>/.runtime.DebugControlReceiver
 *   adb shell am broadcast -a ai.opencode.android.DEBUG_RESET  <pkg>/.runtime.DebugControlReceiver
 *
 * STOP triggers graceful shutdown (H6). RESET wipes the extracted payload so
 * the next extraction recovers (H5 corruption-recovery gate). Guarded by
 * BuildConfig.DEBUG — a release build ignores these broadcasts.
 */
class DebugControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ai.opencode.android.BuildConfig.DEBUG) return
        val manager = RuntimeManager.get(context)
        when (intent.action) {
            "ai.opencode.android.DEBUG_STOP" -> {
                // Directly stop the supervisor. Routing through
                // RuntimeService.stop() (which re-launches the service to
                // deliver a stop action) is unreliable when the app is in the
                // background: the startForegroundService may be denied so the
                // action never arrives, leaving the server running (observed
                // H6: leftover launcher + port 200).
                RuntimeManager.get(context).stop()
            }
            "ai.opencode.android.DEBUG_RESET" -> {
                manager.resetAndRestart()
            }
        }
    }
}
