package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.client.ModelRefCodec
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.ui.theme.ChatTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Phase 10 continuation v4, item 3: the model quick switch, at the top of the chat.
 *
 * Two deliberate restrictions, both from the brief:
 *
 *  1. it lists ONLY the models the user starred in Settings. The catalog is
 *     hundreds of entries (three providers alone list more models than a phone
 *     menu can hold), so "all models" is not a quick switch - it is the Settings
 *     screen, and the last item here goes there;
 *  2. it never leaves the chat screen to change the model: one tap, one menu.
 *
 * Pure: [current] and [starred] are values, picking is a callback. The screen does
 * not know where the list came from, which is what lets the Phase 6 gates render it
 * with fabricated state.
 */
@Composable
fun ModelQuickSwitch(
    current: OpenCodeApi.ModelRef?,
    starred: List<OpenCodeApi.ModelRef>,
    onPick: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val chat = ChatTheme.chat
    val currentText = current?.let { ModelRefCodec.encode(it) } ?: stringResource(R.string.chat_model_none)
    val label = stringResource(R.string.chat_model_switch, currentText)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable { open = true }
            .padding(horizontal = 14.dp)
            .semantics {
                testTag = "model_quick_switch"
                role = Role.Button
                contentDescription = label
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = chat.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { testTag = "model_quick_switch_label" },
        )
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = chat.muted,
            modifier = Modifier.size(20.dp).clearAndSetSemantics { },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.semantics { testTag = "model_quick_switch_menu" },
        ) {
            Text(
                text = stringResource(R.string.chat_model_starred_title),
                style = MaterialTheme.typography.labelSmall,
                color = chat.muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (starred.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_model_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = chat.muted,
                    modifier = Modifier
                        .width(260.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { testTag = "model_quick_switch_empty" },
                )
            } else {
                starred.forEachIndexed { index, ref ->
                    val text = ModelRefCodec.encode(ref)
                    DropdownMenuItem(
                        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            open = false
                            onPick(ref.providerID, ref.modelID)
                        },
                        modifier = Modifier
                            .width(280.dp)
                            .semantics { testTag = "model_pick_$index" },
                    )
                }
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_model_open_settings)) },
                onClick = {
                    open = false
                    onOpenSettings()
                },
                modifier = Modifier.semantics { testTag = "model_quick_switch_settings" },
            )
        }
        Spacer(Modifier.width(0.dp))
    }
}
