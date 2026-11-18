package ai.opencode.android.runtime

import ai.opencode.android.task.BackgroundTask

/**
 * What the runtime notification shows, and the taps that state allows.
 *
 *  - READY: the agent is up and nothing is running.            Taps: Exit
 *  - WORKING: a background task or a chat turn is running.     Taps: Pause, Exit
 *  - PAUSED: the background task is paused.                    Taps: Resume, Exit

 * Pause stops the running task if there is one, otherwise the running chat turn.
 * Either way the session is kept.
 *  - ERROR: the agent stopped, or is restarting after a crash. Taps: Exit
 *  - STARTING: the agent is still coming up.                   Taps: Exit
 *
 * Pure: the service maps live state through here, and the tests pin the table.
 */
object RuntimeNoticeState {

    enum class Kind { STARTING, READY, WORKING, PAUSED, ERROR }

    enum class Tap { PAUSE, RESUME, EXIT }

    fun kindOf(runtime: RuntimeStatus, task: BackgroundTask.Status?, chatBusy: Boolean = false): Kind = when (runtime) {
        RuntimeStatus.FATAL,
        RuntimeStatus.CRASHED_RESTARTING,
        RuntimeStatus.UNSUPPORTED_DEVICE,
        -> Kind.ERROR
        RuntimeStatus.HEALTHY -> when {
            task == BackgroundTask.Status.RUNNING -> Kind.WORKING
            task == BackgroundTask.Status.PAUSED -> Kind.PAUSED
            chatBusy -> Kind.WORKING
            else -> Kind.READY
        }
        RuntimeStatus.EXTRACTING,
        RuntimeStatus.STARTING,
        RuntimeStatus.STOPPED,
        -> Kind.STARTING
    }

    fun tapsOf(kind: Kind): List<Tap> = when (kind) {
        Kind.WORKING -> listOf(Tap.PAUSE, Tap.EXIT)
        Kind.PAUSED -> listOf(Tap.RESUME, Tap.EXIT)
        Kind.READY, Kind.ERROR, Kind.STARTING -> listOf(Tap.EXIT)
    }
}
