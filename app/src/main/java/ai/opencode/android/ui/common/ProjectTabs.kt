package ai.opencode.android.ui.common

import ai.opencode.android.R
import ai.opencode.android.ui.theme.goldAccentBrush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
 * v8 redesign: the four project surfaces - conversation, files, file changes,
 * terminal - as a BOTTOM navigation bar (the owner's explicit v8 decision:
 * thumb-reachable, like the polished chat apps). The contract is unchanged from
 * the v7 strip: same enum, same `project_tab_*` tags, same callback shape -
 * only the placement and the visual language moved, so gate U13/U14 and the
 * device driver's tab walk keep guarding the identical surface.
 *
 * Hand-rolled rather than Material's NavigationBar: the design wants quiet
 * text labels with a soft gold pill behind the active surface, and the stock
 * bar's icon slots and 80 dp height fight that for no benefit here.
 */
enum class ProjectTab { CHAT, FILES, CHANGES, TERMINAL }

@Composable
fun ProjectTabs(
    current: ProjectTab,
    onSelect: (ProjectTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        // v9: no divider, no opaque slab - the bar floats over the screen's
        // golden bloom, and only the active pill carries colour.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem(ProjectTab.CHAT, stringResource(R.string.project_tab_chat), current, onSelect, Modifier.weight(1f))
            TabItem(ProjectTab.FILES, stringResource(R.string.project_tab_files), current, onSelect, Modifier.weight(1f))
            TabItem(ProjectTab.CHANGES, stringResource(R.string.project_tab_changes), current, onSelect, Modifier.weight(1f))
            TabItem(ProjectTab.TERMINAL, stringResource(R.string.project_tab_terminal), current, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TabItem(
    tab: ProjectTab,
    label: String,
    current: ProjectTab,
    onSelect: (ProjectTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = tab == current
    // The tag template stays INLINE in the semantics block: the harness selfcheck
    // resolves gate lookups against literals on `testTag` lines, and a tag built
    // on a separate line is invisible to it.
    val pill = goldAccentBrush()
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .clickable(enabled = !active) { onSelect(tab) }
            .background(
                brush = if (active) pill else SolidColor(Color.Transparent),
                shape = RoundedCornerShape(50),
            )
            .height(38.dp)
            .semantics {
                testTag = "project_tab_${tab.name.lowercase()}"
                role = Role.Tab
                selected = active
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}
