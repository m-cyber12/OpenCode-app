package ai.opencode.android.ui.task

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.opencode.android.R
import ai.opencode.android.task.BackgroundTask
import ai.opencode.android.task.BackgroundTaskHub
import ai.opencode.android.task.BackgroundTaskNotice

/**
 * The status line for the background task: its name, its state, and the taps the
 * state allows (Pause or Resume, Cancel; Dismiss once it has finished). It takes
 * no space when there is no task.
 */
@Composable
fun BackgroundTaskBar() {
    val current by BackgroundTaskHub.current.collectAsState()
    val queued by BackgroundTaskHub.queued.collectAsState()
    val task = current
    if (task == null && queued == null) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        if (task == null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.task_queued, queued.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { BackgroundTaskHub.cancelQueued() }) {
                    Text(stringResource(BackgroundTaskNotice.commandLabelRes(BackgroundTask.Command.CANCEL)))
                }
            }
        } else {
            BackgroundTaskRow(task)
        }
    }
}

@Composable
private fun BackgroundTaskRow(task: BackgroundTaskHub.Current) {
    val status by task.control.status.collectAsState()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.task_title, task.name),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(BackgroundTaskNotice.labelRes(status)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        for (command in status.commands()) {
            TextButton(onClick = { BackgroundTaskHub.perform(command) }) {
                Text(stringResource(BackgroundTaskNotice.commandLabelRes(command)))
            }
        }
    }
}
