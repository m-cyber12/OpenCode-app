package ai.opencode.android.ui.common

import ai.opencode.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * v7 redesign: the four project surfaces - conversation, files, file changes,
 * terminal - presented as one strip of tabs under the project header, so "where
 * am I inside this project" is a single glance. Navigation stays AppRoot's job:
 * this is a row of labelled callbacks, not a router.
 *
 * Hand-rolled rather than Material's TabRow: the design wants quiet text tabs
 * with a short gold underline, left-aligned like the rest of the screen, and
 * TabRow's full-width indicator/ripple chrome fights that for no benefit here.
 */
enum class ProjectTab { CHAT, FILES, CHANGES, TERMINAL }

@Composable
fun ProjectTabs(
    current: ProjectTab,
    onSelect: (ProjectTab) -> Unit,
    modifier: Modifier = Modifier,
    /** Right-aligned slot: the chat parks its Ready/Working status chip here. */
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TabItem(ProjectTab.CHAT, stringResource(R.string.project_tab_chat), current, onSelect)
            TabItem(ProjectTab.FILES, stringResource(R.string.project_tab_files), current, onSelect)
            TabItem(ProjectTab.CHANGES, stringResource(R.string.project_tab_changes), current, onSelect)
            TabItem(ProjectTab.TERMINAL, stringResource(R.string.project_tab_terminal), current, onSelect)
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                trailing()
                Spacer(Modifier.width(12.dp))
            }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun TabItem(
    tab: ProjectTab,
    label: String,
    current: ProjectTab,
    onSelect: (ProjectTab) -> Unit,
) {
    val active = tab == current
    // The tag template stays INLINE in the semantics block: the harness selfcheck
    // resolves gate lookups against literals on `testTag` lines, and a tag built
    // on a separate line is invisible to it.
    Column(
        modifier = Modifier
            .clickable(enabled = !active) { onSelect(tab) }
            .padding(horizontal = 14.dp)
            .semantics {
                testTag = "project_tab_${tab.name.lowercase()}"
                role = Role.Tab
                selected = active
                contentDescription = label
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 10.dp, bottom = 7.dp),
        )
        // A SHORT underline, never fillMaxWidth: inside a Row the first child is
        // measured with the whole remaining width, so a fill here made the Chat
        // tab swallow the entire strip and pushed the other three tabs out of
        // sight - the exact bug gate U13 caught on the emulator (wired=false).
        Box(
            Modifier
                .height(3.dp)
                .width(28.dp)
                .background(
                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                ),
        )
    }
}
