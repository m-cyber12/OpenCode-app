package ai.opencode.android.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Transparent debug control entry point for CI gates.
 *
 * The Phase 4 gates drive stop/reset over `adb`. A manifest-registered
 * [android.content.BroadcastReceiver] proved unreliable here: on API 34 an
 * explicit broadcast to a receiver in a background/cached app is *enqueued but
 * deferred* (observed in logcat: "Enqueued broadcast ... : 0" with no receiver
 * delivery, even after foregrounding the launcher activity), so DEBUG_STOP /
 * DEBUG_RESET never ran and H5/H6 saw the old server still up.
 *
 * An exported Activity has no such restriction: `am start` delivers it
 * immediately and brings the app forward. This activity performs the action
 * and finishes at once (no UI). Guarded by BuildConfig.DEBUG so release builds
 * refuse it.
 *
 *   adb shell am start -n pkg/ai.opencode.android.runtime.DebugControlActivity \
 *       --ei mode 1      (1 = STOP, 2 = RESET)
 */
class DebugControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ai.opencode.android.BuildConfig.DEBUG) {
            Log.w("OpenCode/debug", "DebugControlActivity invoked on non-debug build; ignoring")
            finish()
            return
        }
        val mode = intent.getIntExtra(EXTRA_MODE, MODE_STOP)
        val manager = RuntimeManager.get(applicationContext)
        when (mode) {
            MODE_RESET -> {
                Log.i("OpenCode/debug", "DebugControlActivity RESET -> resetAndRestart()")
                manager.resetAndRestart()
            }
            else -> {
                Log.i("OpenCode/debug", "DebugControlActivity STOP -> stop()")
                manager.stop()
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_STOP = 1
        const val MODE_RESET = 2

        fun stopIntent(context: Context): Intent =
            Intent(context, DebugControlActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MODE, MODE_STOP)

        fun resetIntent(context: Context): Intent =
            Intent(context, DebugControlActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MODE, MODE_RESET)
    }
}
