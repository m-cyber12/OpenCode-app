package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.client.ModelRefCodec
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.ui.theme.ChatTheme
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
 * v9.41 (owner items 21+22), the menu is three things now:
 *
 *  1. the CURRENT model owns the head of the menu, and its thinking levels are
 *     INLINE right under it - the separate "thinking level" section at the
 *     bottom of the menu is gone. Every other model that can think gets a
 *     small triangle on the right of its name; tapping it opens (or closes)
 *     the same inline picker for that model, and choosing a level there picks
 *     the model AND its level in one gesture;
 *  2. a collapsible "Free Models" group (Zen), expanded by default, listed
 *     with a real star rather than a checkmark;
 *  3. the starred shortlist under it, then one "manage" entry to the Providers
 *     surface. The catalog stays where it belongs - hundreds of models do not
 *     fit a phone menu.
 *
 * Pure: every list is a value and every choice is a callback, which is what
 * lets the Phase 6 gates render it with fabricated state.
 */

/** One row of the quick list: the ref, its display name, its thinking variants. */
data class QuickModel(
    val ref: OpenCodeApi.ModelRef,
    val label: String,
    val variants: List<String> = emptyList(),
)

@Composable
fun ModelQuickSwitch(
    current: OpenCodeApi.ModelRef?,
    starred: List<OpenCodeApi.ModelRef>,
    onPick: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The CURRENT model's own reasoning-variant ids, exactly as its catalog row
     * lists them (upstream `Model.variants` keys - low/medium/high/xhigh on the
     * models that have them). Empty = the model reports none, and the menu says
     * so instead of inventing levels the server would ignore.
     */
    variants: List<String> = emptyList(),
    /** The active variant id, "" = the model's default. */
    activeVariant: String = "",
    onPickVariant: (String) -> Unit = {},
    /** v9.41 item 22: the "Free Models" group (all Zen models). */
    freeModels: List<QuickModel> = emptyList(),
    /** Thinking variants for any listed model, keyed `provider/model`. */
    variantsOf: (String, String) -> List<String> = { _, _ -> emptyList() },
    /** v9.41: pick model AND level in one go (a level tap inside a row). */
    onPickWithVariant: (String, String, String) -> Unit = { p, m, _ -> onPick(p, m) },
    /**
     * v9.42 (owner): "if we can't make model loading any faster, at least
     * replace 'No Model' with 'Loading' and an animation". True while the
     * provider catalog has not arrived - the capsule then says what is
     * happening instead of claiming there is no model.
     */
    modelsLoading: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    var freeOpen by remember { mutableStateOf(true) } // expanded by default (owner)
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val chat = ChatTheme.chat
    val currentText = when {
        current != null -> ModelRefCodec.encode(current)
        modelsLoading -> stringResource(R.string.chat_model_loading)
        else -> stringResource(R.string.chat_model_none)
    }
    val label = stringResource(R.string.chat_model_switch, currentText)

    // v9 premium pass, after the Gemini reference: the switch is a compact
    // glass capsule in the header row (hamburger - model - status), showing
    // just the model's own name. The accessibility label keeps the full
    // "Model: <name>" sentence - it is what the real-device driver taps.
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(chat.toolContainer)
            .border(1.dp, chat.toolBorder, RoundedCornerShape(50))
            .clickable { open = true }
            .heightIn(min = 36.dp)
            .padding(start = 14.dp, end = 6.dp)
            .semantics {
                testTag = "model_quick_switch"
                role = Role.Button
                contentDescription = label
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = currentText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // v9.4 (owner's bug 5): the label yields to the row instead of
            // pinning a fixed width - inside the header the capsule now gets
            // exactly the space the other controls leave, and a long model
            // name ellipsizes right here.
            modifier = Modifier
                .weight(1f, fill = false)
                .semantics { testTag = "model_quick_switch_label" },
        )
        if (modelsLoading && current == null) {
            // v9.42 (owner): the animation that makes "Loading" a state and
            // not a word - one dot breathing, cleared from the a11y tree
            // (the capsule's own label already says "Loading").
            val infinite = rememberInfiniteTransition(label = "model_loading")
            val breathe by infinite.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse),
                label = "model_loading_breathe",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(breathe)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clearAndSetSemantics { },
            )
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = chat.muted,
            modifier = Modifier.size(20.dp).clearAndSetSemantics { },
        )
        // The menu matches the capsule: a soft rounded card, not the stock
        // sharp-cornered sheet.
        MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(20.dp))) {
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.semantics { testTag = "model_quick_switch_menu" },
        ) {
            // ---- the current model, and nothing else at the top --------------
            // v9.42 (owner): "the model selector menu has become too large -
            // make it more minimal". The thinking-level block that used to sit
            // here is GONE: levels are one tap away, inside the row's own
            // triangle, and the menu opens on the models themselves again.
            // The triangle stays (owner's explicit "don't remove the
            // triangles next to the models"); only the separate section went.
            if (current != null) {
                val variantSuffix = activeVariant.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                Text(
                    text = currentText + variantSuffix,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .width(280.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { testTag = "model_current_row" },
                )
                HorizontalDivider(color = chat.toolBorder, modifier = Modifier.padding(vertical = 4.dp))
            }

            // ---- Free Models (Zen), collapsible, expanded by default --------
            if (freeModels.isNotEmpty()) {
                QuickGroupHeader(
                    title = stringResource(R.string.chat_model_free_title),
                    expanded = freeOpen,
                    tag = "model_free_header",
                    onToggle = { freeOpen = !freeOpen },
                )
                if (freeOpen) {
                    freeModels.forEachIndexed { index, qm ->
                        ModelLineRow(
                            model = qm,
                            variantsOf = variantsOf,
                            expanded = expandedKey == "free_$index",
                            onToggleExpand = {
                                expandedKey = if (expandedKey == "free_$index") null else "free_$index"
                            },
                            onPickModel = {
                                open = false
                                onPick(qm.ref.providerID, qm.ref.modelID)
                            },
                            onPickLevel = { v ->
                                open = false
                                onPickWithVariant(qm.ref.providerID, qm.ref.modelID, v)
                            },
                            rowTag = "model_free_$index",
                            levelTag = "free_vt_$index",
                            current = current,
                        )
                    }
                    HorizontalDivider(color = chat.toolBorder, modifier = Modifier.padding(vertical = 4.dp))
                }
            }

            // ---- the starred shortlist ---------------------------------------
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
                    val pid = ref.providerID
                    val mid = ref.modelID
                    ModelLineRow(
                        model = QuickModel(ref, ModelRefCodec.encode(ref), variantsOf(pid, mid)),
                        variantsOf = variantsOf,
                        expanded = expandedKey == "star_$index",
                        onToggleExpand = {
                            expandedKey = if (expandedKey == "star_$index") null else "star_$index"
                        },
                        onPickModel = {
                            open = false
                            onPick(pid, mid)
                        },
                        onPickLevel = { v ->
                            open = false
                            onPickWithVariant(pid, mid, v)
                        },
                        rowTag = "model_pick_${index}",
                        levelTag = "star_vt_$index",
                        current = current,
                    )
                }
            }
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = chat.muted.copy(alpha = 0.7f),
                            modifier = Modifier.size(15.dp).clearAndSetSemantics { },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.chat_model_open_settings))
                    }
                },
                onClick = {
                    open = false
                    onOpenSettings()
                },
                modifier = Modifier.semantics { testTag = "model_quick_switch_settings" },
            )
        }
        }
        Spacer(Modifier.width(0.dp))
    }
}

/** The "Free Models" group header: label + fold marker, tappable. */
@Composable
private fun QuickGroupHeader(
    title: String,
    expanded: Boolean,
    tag: String,
    onToggle: () -> Unit,
) {
    val chat = ChatTheme.chat
    Row(
        modifier = Modifier
            .width(280.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics { testTag = tag; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = chat.muted,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = chat.muted,
            modifier = Modifier.size(16.dp).clearAndSetSemantics { },
        )
    }
}

/**
 * One model line in the quick menu (v9.41 item 22): a real STAR leads the row
 * (gold when this is the active model's group member... the star here is the
 * owner's replacement for the checkmark - the selected model still keeps its
 * name in normal weight); the name; and for thinking-capable models a small
 * TRIANGLE on the right that opens the level picker inline under the row.
 */
@Composable
private fun ModelLineRow(
    model: QuickModel,
    variantsOf: (String, String) -> List<String>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPickModel: () -> Unit,
    onPickLevel: (String) -> Unit,
    rowTag: String,
    levelTag: String,
    current: OpenCodeApi.ModelRef?,
) {
    val chat = ChatTheme.chat
    val isSelected = current != null &&
        current.providerID == model.ref.providerID && current.modelID == model.ref.modelID
    val levels = model.variants.ifEmpty { variantsOf(model.ref.providerID, model.ref.modelID) }
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    // v9.41 (owner item 21): a REAL star replaces the checkmark.
                    // (material-icons-core has Star but no StarBorder, so the
                    // unstarred form is the same star drawn quiet.)
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else chat.muted.copy(alpha = 0.45f),
                    modifier = Modifier
                        .size(14.dp)
                        .padding(end = 8.dp)
                        .clearAndSetSemantics { },
                )
                Text(model.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingIcon = {
            if (levels.isNotEmpty()) {
                // The little triangle: opens/closes the inline level picker.
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.chat_thinking_expand),
                    tint = chat.muted,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleExpand)
                        .semantics { testTag = "${levelTag}_toggle" },
                )
            } else {
                Spacer(Modifier.width(4.dp))
            }
        },
        onClick = onPickModel,
        modifier = Modifier
            .width(300.dp)
            .semantics {
                testTag = rowTag
                contentDescription = model.label
            },
    )
    if (expanded && levels.isNotEmpty()) {
        levels.forEachIndexed { index, id ->
            ThinkingRow(
                label = id,
                active = false,
                onClick = { onPickLevel(id) },
                modifier = Modifier.semantics { testTag = "${levelTag}_$index" },
            )
        }
    }
}

/**
 * One thinking-level row: the variant id as the server spells it, a gold check
 * on the active one. The row's accessible name is the full "Thinking level: x"
 * sentence so the id-only label still reads as what it does.
 */
@Composable
private fun ThinkingRow(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenuItem(
        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            if (active) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp).clearAndSetSemantics { },
                )
            }
        },
        onClick = onClick,
        modifier = Modifier.width(280.dp).then(modifier),
    )
}
