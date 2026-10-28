package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.client.ModelRefCodec
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.ui.common.rememberLoadingPulse
import ai.opencode.android.ui.theme.ChatTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The model quick switch, at the top of the chat.
 *
 * v11 (owner, B.107): the menu is a HALF-SCREEN SHEET now, sliding up from the
 * bottom the way Claude's model picker does, instead of a small popup. It is a
 * lazy list, so opening it composes only the first screenful: the owner's
 * "takes seconds to open with no feedback" was the whole catalog composed in a
 * popup on every open. The sheet appears on the tap itself (no waiting for the
 * list), and nothing about a collapsed group is composed at all.
 *
 * The order, top to bottom:
 *  1. the CURRENT model, with its active thinking level;
 *  2. "Free Models" (Zen), collapsible, expanded by default;
 *  3. every CONNECTED provider, pinned directly under Zen. Each is a fold header
 *     (owner: "pin connected providers directly below Zen"), folded by default so
 *     the starred shortlist below it stays on screen;
 *  4. the starred shortlist;
 *  5. every other provider, a fold header too, folded by default, so a long
 *     catalog never crowds the menu;
 *  6. one "choose models in Settings" entry.
 *
 * A model with thinking levels shows a small triangle; tapping it opens the
 * level list inline, and a level tap picks the model AND its level in one go.
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

/** One provider group of the quick menu (every provider except Zen). */
data class QuickProvider(
    val id: String,
    val name: String,
    val models: List<QuickModel>,
    /** Connected providers are pinned under Zen; their headers show, their models fold. */
    val connected: Boolean,
)

// v9.42 (owner): the four brightness frames of the capsule's loading dot.
private val MODEL_LOADING_FRAMES = floatArrayOf(0.25f, 0.55f, 1f, 0.55f)

/**
 * A bottom sheet that takes about HALF the screen. It opens at that height (no
 * partial state to drag through), closes with its own slide-down, and the tag
 * is on the sheet itself so the gates can find it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HalfScreenSheet(
    tag: String,
    onDismiss: () -> Unit,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val halfHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    // Close with the slide-down animation first, then leave composition.
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.semantics { this.testTag = tag },
    ) {
        Box(Modifier.fillMaxWidth().height(halfHeight)) {
            content(close)
        }
    }
}

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
    /** v11: every provider except Zen - connected ones first, then the rest. */
    providerGroups: List<QuickProvider> = emptyList(),
) {
    var open by remember { mutableStateOf(false) }
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
            // four brightness frames, stepped by the shared pulse (bounded by
            // composition - see rememberLoadingPulse)
            val breathe = MODEL_LOADING_FRAMES[rememberLoadingPulse(360) % MODEL_LOADING_FRAMES.size]
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
    }

    if (open) {
        HalfScreenSheet(tag = "model_quick_switch_menu", onDismiss = { open = false }) { close ->
            QuickModelList(
                current = current,
                currentText = currentText,
                activeVariant = activeVariant,
                starred = starred,
                freeModels = freeModels,
                providerGroups = providerGroups,
                variantsOf = variantsOf,
                onPick = { p, m ->
                    close()
                    onPick(p, m)
                },
                onPickWithVariant = { p, m, v ->
                    close()
                    onPickWithVariant(p, m, v)
                },
                onOpenSettings = {
                    close()
                    onOpenSettings()
                },
            )
        }
    }
}

/** The lazy body of the sheet. Every group is a lazy section; a fold costs nothing. */
@Composable
private fun QuickModelList(
    current: OpenCodeApi.ModelRef?,
    currentText: String,
    activeVariant: String,
    starred: List<OpenCodeApi.ModelRef>,
    freeModels: List<QuickModel>,
    providerGroups: List<QuickProvider>,
    variantsOf: (String, String) -> List<String>,
    onPick: (String, String) -> Unit,
    onPickWithVariant: (String, String, String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val chat = ChatTheme.chat
    // One expanded row at a time across the whole sheet: the key names it.
    var expandedKey by remember { mutableStateOf<String?>(null) }
    var freeOpen by remember { mutableStateOf(true) } // expanded by default (owner)
    // Every provider group folds by default (a tap opens it). Keyed by provider id.
    val groupOpen = remember { mutableStateMapOf<String, Boolean>() }
    fun isOpen(group: QuickProvider): Boolean = groupOpen[group.id] ?: false

    LazyColumn(
        modifier = Modifier.fillMaxSize().semantics { testTag = "model_quick_list" },
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // ---- the current model, and its active thinking level ------------
        if (current != null) {
            item(key = "current") {
                val variantSuffix = activeVariant.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                Column {
                    Text(
                        text = currentText + variantSuffix,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .semantics { testTag = "model_current_row" },
                    )
                    HorizontalDivider(color = chat.toolBorder)
                }
            }
        }

        // ---- Free Models (Zen), collapsible, expanded by default --------
        if (freeModels.isNotEmpty()) {
            item(key = "free_head") {
                QuickGroupHeader(
                    title = stringResource(R.string.chat_model_free_title),
                    expanded = freeOpen,
                    tag = "model_free_header",
                    onToggle = { freeOpen = !freeOpen },
                )
            }
            if (freeOpen) {
                itemsIndexed(freeModels, key = { i, _ -> "free_$i" }) { index, qm ->
                    ModelLineRow(
                        model = qm,
                        variantsOf = variantsOf,
                        expanded = expandedKey == "free_$index",
                        onToggleExpand = {
                            expandedKey = if (expandedKey == "free_$index") null else "free_$index"
                        },
                        onPickModel = { onPick(qm.ref.providerID, qm.ref.modelID) },
                        onPickLevel = { v -> onPickWithVariant(qm.ref.providerID, qm.ref.modelID, v) },
                        rowTag = "model_free_$index",
                        levelTag = "free_vt_$index",
                        current = current,
                    )
                }
            }
        }

        // ---- connected providers, pinned directly under Zen ---------------
        for (group in providerGroups.filter { it.connected }) {
            quickProviderGroup(
                group = group,
                open = isOpen(group),
                onToggle = { groupOpen[group.id] = !isOpen(group) },
                expandedKey = expandedKey,
                onExpandKey = { k -> expandedKey = if (expandedKey == k) null else k },
                variantsOf = variantsOf,
                current = current,
                onPick = onPick,
                onPickWithVariant = onPickWithVariant,
            )
        }

        // ---- the starred shortlist ---------------------------------------
        item(key = "starred_head") {
            Text(
                text = stringResource(R.string.chat_model_starred_title),
                style = MaterialTheme.typography.labelSmall,
                color = chat.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (starred.isEmpty()) {
            item(key = "starred_empty") {
                Text(
                    text = stringResource(R.string.chat_model_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = chat.muted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .semantics { testTag = "model_quick_switch_empty" },
                )
            }
        } else {
            itemsIndexed(starred, key = { i, _ -> "star_$i" }) { index, ref ->
                val pid = ref.providerID
                val mid = ref.modelID
                ModelLineRow(
                    model = QuickModel(ref, ModelRefCodec.encode(ref), variantsOf(pid, mid)),
                    variantsOf = variantsOf,
                    expanded = expandedKey == "star_$index",
                    onToggleExpand = {
                        expandedKey = if (expandedKey == "star_$index") null else "star_$index"
                    },
                    onPickModel = { onPick(pid, mid) },
                    onPickLevel = { v -> onPickWithVariant(pid, mid, v) },
                    rowTag = "model_pick_$index",
                    levelTag = "star_vt_$index",
                    current = current,
                )
            }
        }

        // ---- every other provider, folded by default ----------------------
        for (group in providerGroups.filter { !it.connected }) {
            quickProviderGroup(
                group = group,
                open = isOpen(group),
                onToggle = { groupOpen[group.id] = !isOpen(group) },
                expandedKey = expandedKey,
                onExpandKey = { k -> expandedKey = if (expandedKey == k) null else k },
                variantsOf = variantsOf,
                current = current,
                onPick = onPick,
                onPickWithVariant = onPickWithVariant,
            )
        }

        item(key = "manage") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .semantics {
                        testTag = "model_quick_switch_settings"
                        role = Role.Button
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = chat.muted.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp).clearAndSetSemantics { },
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.chat_model_open_settings), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * One provider group below Zen: a fold header, and - only while it is open - its
 * models. Tags are `conn_<provider>_<i>` or `other_<provider>_<i>`, deliberately
 * outside the `model_pick_` and `star_vt_` families the starred list owns.
 */
private fun LazyListScope.quickProviderGroup(
    group: QuickProvider,
    open: Boolean,
    onToggle: () -> Unit,
    expandedKey: String?,
    onExpandKey: (String) -> Unit,
    variantsOf: (String, String) -> List<String>,
    current: OpenCodeApi.ModelRef?,
    onPick: (String, String) -> Unit,
    onPickWithVariant: (String, String, String) -> Unit,
) {
    val prefix = (if (group.connected) "conn_" else "other_") + group.id
    item(key = "head_$prefix") {
        QuickGroupHeader(
            title = group.name,
            expanded = open,
            tag = "model_group_${group.id}",
            onToggle = onToggle,
        )
    }
    if (open) {
        itemsIndexed(group.models, key = { i, _ -> "${prefix}_$i" }) { index, qm ->
            val key = "${prefix}_$index"
            ModelLineRow(
                model = qm,
                variantsOf = variantsOf,
                expanded = expandedKey == key,
                onToggleExpand = { onExpandKey(key) },
                onPickModel = { onPick(qm.ref.providerID, qm.ref.modelID) },
                onPickLevel = { v -> onPickWithVariant(qm.ref.providerID, qm.ref.modelID, v) },
                rowTag = "${prefix}_pick_$index",
                levelTag = "${prefix}_vt_$index",
                current = current,
            )
        }
    }
}

/** A group header: label + fold marker, tappable. */
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
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics {
                testTag = tag
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = chat.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = chat.muted,
            modifier = Modifier.size(18.dp).clearAndSetSemantics { },
        )
    }
}

/**
 * One model line: a star (gold when it is the active model), the name, and for
 * thinking-capable models a TRIANGLE that opens the level list inline. The name
 * and the triangle are SIBLINGS, not one clickable parent, so each keeps its own
 * tag in the semantics tree.
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
    // Read here, not inside a semantics block: stringResource is a composable call.
    val triangleLabel = stringResource(R.string.chat_thinking_expand) + ": " + model.label
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(onClick = onPickModel)
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .semantics {
                    testTag = rowTag
                    contentDescription = model.label
                    role = Role.Button
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                // v9.41 (owner item 21): a REAL star replaces the checkmark.
                // (material-icons-core has Star but no StarBorder, so the
                // unstarred form is the same star drawn quiet.)
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else chat.muted.copy(alpha = 0.45f),
                modifier = Modifier.size(14.dp).clearAndSetSemantics { },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                model.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (levels.isNotEmpty()) {
            // The little triangle: opens/closes the inline level picker.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleExpand)
                    .semantics {
                        testTag = "${levelTag}_toggle"
                        role = Role.Button
                        contentDescription = triangleLabel
                    },
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = chat.muted,
                    modifier = Modifier.size(22.dp).clearAndSetSemantics { },
                )
            }
        }
    }
    if (expanded && levels.isNotEmpty()) {
        levels.forEachIndexed { index, id ->
            ThinkingRow(
                label = id,
                onClick = { onPickLevel(id) },
                modifier = Modifier.semantics { testTag = "${levelTag}_$index" },
            )
        }
    }
}

/** The level-list row under a model: the variant id as the server spells it. */
@Composable
private fun ThinkingRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(start = 44.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
