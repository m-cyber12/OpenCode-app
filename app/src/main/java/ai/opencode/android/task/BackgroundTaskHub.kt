package ai.opencode.android.task

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The one place the bar, the notification and the watch loop meet for background
 * tasks. It is process-wide: the notification's buttons arrive as broadcasts in
 * the same process, and they reach this hub.
 */
object BackgroundTaskHub {
    class Current(val name: String, val control: BackgroundTask.Control)

    private val currentFlow = MutableStateFlow<Current?>(null)
    val current: StateFlow<Current?> = currentFlow.asStateFlow()

    private val queuedFlow = MutableStateFlow<String?>(null)
    val queued: StateFlow<String?> = queuedFlow.asStateFlow()

    /** The current task's name and status, for the notification. Null when there is no task. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val notice: Flow<Pair<String, BackgroundTask.Status>?> =
        currentFlow.flatMapLatest { task ->
            if (task == null) {
                flowOf<Pair<String, BackgroundTask.Status>?>(null)
            } else {
                task.control.status.map { status -> task.name to status }
            }
        }

    /** A spec was found but the agent is not up yet: it waits, and the bar says so. */
    fun setQueued(name: String?) {
        queuedFlow.value = name
    }

    /** Starts the bar for [name]. The previous finished task, if any, is replaced. */
    fun begin(name: String): BackgroundTask.Control {
        val control = BackgroundTask.Control()
        queuedFlow.value = null
        currentFlow.value = Current(name, control)
        return control
    }

    fun perform(command: BackgroundTask.Command) {
        when (command) {
            BackgroundTask.Command.PAUSE -> currentFlow.value?.control?.pause()
            BackgroundTask.Command.RESUME -> currentFlow.value?.control?.resume()
            BackgroundTask.Command.CANCEL -> currentFlow.value?.control?.cancel()
            BackgroundTask.Command.DISMISS -> dismiss()
        }
    }

    /** Clears a finished task from the bar. An active task cannot be dismissed. */
    fun dismiss() {
        val task = currentFlow.value ?: return
        if (!task.control.status.value.active) currentFlow.value = null
    }

    /** The watch loop threw. The bar must not stay on an active state that will never end. */
    fun failCurrent() {
        val control = currentFlow.value?.control ?: return
        if (control.status.value.active) control.setStatus(BackgroundTask.Status.FAILED)
    }

    /** Keeps the notification in step with the task until the process ends. */
    suspend fun publishNotices(context: android.content.Context) {
        notice.collect { pair ->
            if (pair == null) {
                BackgroundTaskNotice.clear(context)
            } else {
                BackgroundTaskNotice.post(context, pair.first, pair.second)
            }
        }
    }
}
