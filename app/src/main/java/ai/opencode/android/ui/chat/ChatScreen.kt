package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.client.Transcript
import ai.opencode.android.client.UiError
import ai.opencode.android.ui.common.AvailabilityBanner
import ai.opencode.android.ui.common.RedoGlyph
import ai.opencode.android.ui.common.ProjectTab
import ai.opencode.android.ui.common.ProjectTabs
import ai.opencode.android.ui.common.RuntimeSummary
import ai.opencode.android.client.OpenCodeApi
import ai.opencode.android.client.TodoItem
import ai.opencode.android.client.TodoParser
import ai.opencode.android.client.ToolKind
import ai.opencode.android.client.ToolKinds
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import ai.opencode.android.ui.theme.goldAccentBrush
import ai.opencode.android.ui.theme.goldenBackdrop
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.delay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The conversation screen: the app's main surface.
 *
 * It is a pure function of [OpenCodeRepository.UiState] plus a [RuntimeSummary]
 * plus callbacks - no repository, no runtime singleton, no preferences (a static
 * check enforces that, and it is what lets the Phase 6 UI gates render a 300
 * message transcript, a failed turn, a tool call with a diff and a permission ask
 * without a device runtime, a model or a key).
 *
 * Layout decisions worth stating:
 *   * the transcript is a real [LazyColumn] with stable keys, so a long
 *     conversation composes only what is on screen;
 *   * blocking asks (permissions, questions) are pinned above the composer rather
 *     than dropped into the scroll: upstream blocks the turn on them, so hiding
 *     one behind a scroll position would strand the agent;
 *   * the list pins to the newest turn while the user is at the bottom and stops
 *     pinning the moment they scroll up, with a "N new" affordance to jump back;
 *   * the composer is disabled only when the LOCAL agent cannot take a prompt. A
 *     provider failure leaves it enabled on purpose: the app is fine, and the way
 *     out is another attempt or a fixed key.
 */
@Composable
fun ChatScreen(
    state: OpenCodeRepository.UiState,
    runtime: RuntimeSummary,
    availability: AgentAvailability,
    projectName: String,
    onSend: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    /** v9.1: providers manage themselves on their own surface; defaulted so the gates' call sites stay valid. */
    onOpenProviders: () -> Unit = onOpenSettings,
    /** v9.24: the Add-ons surface (app-downloaded artifacts agents reuse). */
    onOpenAddons: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    /** v7 redesign: the Changes and Terminal tabs under the project header. */
    onOpenChanges: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    /** v4 item 3: pick one of the starred models without a trip to Settings. */
    onPickModel: (String, String) -> Unit = { _, _ -> },
    /** Build/plan: which built-in agent the next prompt runs as. */
    onPickMode: (String) -> Unit = {},
    /** The reasoning-variant id for the next prompt; "" = model default. */
    onPickThinking: (String) -> Unit = {},
    /** v9.19 token saver: upstream /compact - summarize the history server-side. */
    onCompactSession: () -> Unit = {},
    onPermissionReply: (String, String) -> Unit,
    onQuestionSubmit: (String, List<List<String>>) -> Unit,
    onQuestionSkip: (String) -> Unit,
    /**
     * v9.31 (owner): plan approval is the MODEL's ask now, via the question
     * tool. Fired when, in Plan mode, the user submits an answer carrying
     * the approve option - AppRoot sends the execution prompt once the plan
     * turn has ended.
     */
    onPlanApproved: () -> Unit = {},
    /** v9.31 (owner): GitHub projects carry the mark in the menu tile too. */
    githubProject: Boolean = false,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissBanner: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
    listState: LazyListState = rememberLazyListState(),
) {
    val messages = state.messages
    val sessionTitle = state.selectedInfo?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_untitled)
    val projectLabel = projectName.ifEmpty { stringResource(R.string.projects_title) }
    val projectsDescription = stringResource(R.string.chat_open_projects)

    Column(
        modifier = modifier
            .fillMaxSize()
            // v9: the whole conversation floats over true black that settles
            // into a faint golden bloom at the base (a static brush - the
            // glow never animates). Header, composer and tab bar are
            // transparent so the one backdrop runs edge to edge.
            .background(goldenBackdrop())
            .semantics { testTag = "chat_screen" },
    ) {
        // v9 premium pass, after the Gemini reference: ONE header row - the
        // hamburger leads, the model capsule sits beside it, the status orb
        // holds the far right. The project/session identity moved INSIDE the
        // hamburger menu (glass card at its very top), so the row above the
        // conversation carries exactly three quiet controls and nothing else.
        ChatHeader(
            projectLabel = projectLabel,
            sessionTitle = sessionTitle,
            projectsDescription = projectsDescription,
            githubProject = githubProject,
            busy = state.busy,
            availability = availability,
            model = state.model,
            starred = state.starredModels,
            thinkingVariants = state.providers?.variantsOf(state.model) ?: emptyList(),
            thinkingVariant = state.thinkingVariant,
            agentMode = state.agentMode,
            onPickModel = onPickModel,
            onPickMode = onPickMode,
            onPickThinking = onPickThinking,
            onOpenProjects = onOpenProjects,
            onNewSession = onNewSession,
            onOpenSettings = onOpenSettings,
            onOpenProviders = onOpenProviders,
            onOpenAddons = onOpenAddons,
            onCompactSession = onCompactSession,
        )

        StatusArea(state = state, runtime = runtime, availability = availability, onDismissBanner = onDismissBanner)

        // v9.32 (owner): questions render INLINE in the chat - a small card at
        // the tail of the transcript, right below whatever the agent just said.
        // For a plan that means directly below the plan text, so the user can
        // read the whole plan and then decide (the old sheet pinned the card
        // over the composer and HID the plan it asked about). Permissions stay
        // in the AskArea sheet - they are interruptions, not conversation.
        //
        // v9.31 (owner) plan contract, unchanged - only WHERE it renders moved:
        // the plan confirmation is the MODEL's ask through the question tool;
        // when the user submits an answer that selects the approve option
        // (brief-pinned label), the app switches the agent to build and - via
        // onPlanApproved - AppRoot sends the execution prompt once this turn
        // has ended.
        val approveOption = stringResource(R.string.chat_plan_approve_option)
        TranscriptPane(
            messages = messages,
            busy = state.busy,
            now = now,
            listState = listState,
            onRetry = if (state.canRetryTurn) onRetry else null,
            onUndo = if (state.canRetryTurn) onUndo else null,
            question = state.pendingQuestions.firstOrNull(),
            onQuestionSubmit = { id, answers ->
                onQuestionSubmit(id, answers)
                if (state.agentMode == OpenCodeRepository.AGENT_PLAN &&
                    answers.any { group -> group.any { it.equals(approveOption, ignoreCase = true) } }
                ) {
                    onPickMode(OpenCodeRepository.AGENT_BUILD)
                    onPlanApproved()
                }
            },
            onQuestionSkip = onQuestionSkip,
            modifier = Modifier.weight(1f),
        )

        AskArea(
            asks = state.pendingAsks,
            onPermissionReply = onPermissionReply,
        )

        AttachmentTray(attachments = state.attachments, onRemove = onRemoveAttachment)

        Composer(
            draft = state.draft,
            onDraftChange = onDraftChange,
            onSend = { onSend(state.draft) },
            onStop = onStop,
            onAttach = onAttach,
            busy = state.busy,
            canSend = UiError.canSend(availability),
            canRedo = state.canRedo,
            onRedo = onRedo,
        )

        // v8: the project surfaces live in a bottom bar now (owner's decision:
        // thumb-reachable). Same tags, same callbacks as the v7 top strip.
        ProjectTabs(
            current = ProjectTab.CHAT,
            onSelect = { tab ->
                when (tab) {
                    ProjectTab.FILES -> onOpenFiles()
                    ProjectTab.CHANGES -> onOpenChanges()
                    ProjectTab.TERMINAL -> onOpenTerminal()
                    ProjectTab.CHAT -> Unit
                }
            },
        )
    }
}

// ---- header -----------------------------------------------------------------

/**
 * The v9 chat header, after the Gemini reference: one row. The hamburger menu
 * leads on the left; the model capsule sits beside it; the status orb holds
 * the far right. The project/session identity (the code-glyph tile and both
 * names) lives INSIDE the menu now, as a glass card at its very top - tapping
 * it opens the project list, exactly as the old header block did. Every tag,
 * label and callback the gates and the driver navigate by is the v8 set:
 * `chat_menu`, `new_session`, `open_settings`, `model_quick_switch`,
 * `chat_status_pill` - plus the mode switch (`mode_switch`) to the left of
 * the status orb.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChatHeader(
    projectLabel: String,
    sessionTitle: String,
    projectsDescription: String,
    githubProject: Boolean,
    busy: Boolean,
    availability: AgentAvailability,
    model: OpenCodeApi.ModelRef?,
    starred: List<OpenCodeApi.ModelRef>,
    thinkingVariants: List<String>,
    thinkingVariant: String,
    agentMode: String,
    onPickModel: (String, String) -> Unit,
    onPickMode: (String) -> Unit,
    onPickThinking: (String) -> Unit,
    onOpenProjects: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAddons: () -> Unit,
    onCompactSession: () -> Unit,
) {
    val chat = ChatTheme.chat
    val newLabel = stringResource(R.string.chat_new_conversation)
    val settingsLabel = stringResource(R.string.chat_open_settings)
    val providersLabel = stringResource(R.string.chat_open_providers)
    val addonsLabel = stringResource(R.string.chat_open_addons)
    val compactLabel = stringResource(R.string.chat_compact_session)
    val menuLabel = stringResource(R.string.chat_open_menu)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            // v9.1: the hamburger sits in its own glass circle, like the other
            // header controls - the row reads as three floating glass pieces.
            // v9.6 (owner, second pass on the size): built EXACTLY like the
            // status orb - a 36dp clickable Surface - instead of an
            // IconButton, whose Material minimum-touch-target plumbing kept
            // it visually larger than its neighbours.
            Surface(
                onClick = { menuOpen = true },
                color = chat.toolContainer,
                shape = CircleShape,
                border = BorderStroke(1.dp, chat.toolBorder),
                modifier = Modifier
                    .size(36.dp)
                    .semantics { testTag = "chat_menu" },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = menuLabel,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            // The menu itself rounds to a soft card (the stock extraSmall corner
            // reads sharp against the black backdrop).
            MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(22.dp))) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // The identity card: the code-glyph tile on a golden
                    // gradient, project name and session title beside it, the
                    // whole card a floating glass pane. One tap = the project
                    // list, exactly what tapping the old header block did.
                    Surface(
                        onClick = {
                            menuOpen = false
                            onOpenProjects()
                        },
                        color = chat.toolContainer,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, chat.toolBorder),
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .widthIn(min = 236.dp)
                            .semantics { contentDescription = projectsDescription },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(goldAccentBrush(), RoundedCornerShape(13.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                // v9.31 (owner): the menu's identity tile shows
                                // the SAME mark as the projects page - a GitHub
                                // project is recognizable everywhere.
                                if (githubProject) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_github_mark),
                                        contentDescription = stringResource(R.string.projects_github_label),
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.project_glyph),
                                        style = MonoSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = projectLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = sessionTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chat.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    DropdownMenuItem(
                        text = { Text(providersLabel) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = chat.muted,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onOpenProviders()
                        },
                        modifier = Modifier.semantics {
                            testTagsAsResourceId = true
                            testTag = "open_providers"
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(newLabel) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                tint = chat.muted,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onNewSession()
                        },
                        modifier = Modifier.semantics {
                            testTagsAsResourceId = true
                            testTag = "new_session"
                        },
                    )
                    // v9.19 token saver: upstream's /compact, always reachable
                    // from the menu (the smart chip above the composer only
                    // appears once the conversation is actually heavy).
                    // v9.24 (owner): Add-ons - the app downloads big artifacts
                    // (the Next wasm compiler) once, agents reuse them free.
                    DropdownMenuItem(
                        text = { Text(addonsLabel) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Build,
                                contentDescription = null,
                                tint = chat.muted,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onOpenAddons()
                        },
                        modifier = Modifier.semantics {
                            testTagsAsResourceId = true
                            testTag = "open_addons"
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(compactLabel) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                tint = chat.muted,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onCompactSession()
                        },
                        modifier = Modifier.semantics {
                            testTagsAsResourceId = true
                            testTag = "compact_session"
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(settingsLabel) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = null,
                                tint = chat.muted,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        },
                        modifier = Modifier.semantics {
                            testTagsAsResourceId = true
                            testTag = "open_settings"
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        // Models are starred on the Providers surface now, so the quick
        // switch's manage entry leads there (in the gates the two callbacks
        // are the same lambda, which keeps the U10 contract meaningful).
        // v9.4 (owner's bug 5): the capsule owns the flexible middle of the
        // row and shrinks FIRST - a long model name ellipsizes inside it
        // instead of shoving the mode switch and the status orb off screen.
        // Nothing left the header; the fixed-size pieces are simply laid out
        // after the only elastic one.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            ModelQuickSwitch(
                current = model,
                starred = starred,
                onPick = onPickModel,
                onOpenSettings = onOpenProviders,
                variants = thinkingVariants,
                activeVariant = thinkingVariant,
                onPickVariant = onPickThinking,
            )
        }
        Spacer(Modifier.width(8.dp))
        ModeSwitch(agentMode = agentMode, onPickMode = onPickMode)
        Spacer(Modifier.width(8.dp))
        StatusOrb(busy = busy, availability = availability)
    }
}

// v9.31 (owner): PlanApproveBar REMOVED - it appeared after any first
// plan-mode reply even when no plan existed. The confirmation is the model's
// own question-tool ask now; see the AskArea wiring above.

/**
 * The build/plan switch: a glass capsule to the LEFT of the status orb (the
 * owner's brief), one word - Build or Plan. Tapping opens a two-row menu, each
 * row the mode's name over one line of what it really is: upstream's own
 * `build` ("executes tools based on configured permissions") and `plan`
 * ("disallows all edit tools") agents, sent verbatim as `PromptInput.agent`.
 */
@Composable
private fun ModeSwitch(
    agentMode: String,
    onPickMode: (String) -> Unit,
) {
    val chat = ChatTheme.chat
    var open by remember { mutableStateOf(false) }
    val plan = agentMode == OpenCodeRepository.AGENT_PLAN
    val currentLabel = stringResource(if (plan) R.string.chat_mode_plan else R.string.chat_mode_build)
    val switchLabel = stringResource(R.string.chat_mode_switch, currentLabel)
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(chat.toolContainer)
                .border(1.dp, chat.toolBorder, RoundedCornerShape(50))
                .clickable { open = true }
                .heightIn(min = 36.dp)
                .padding(horizontal = 13.dp)
                .semantics {
                    testTag = "mode_switch"
                    role = Role.Button
                    contentDescription = switchLabel
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A tiny gold dot marks the non-default mode at a glance; Build
            // stays a quiet word, exactly as "standard" should look.
            if (plan) {
                Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(20.dp))) {
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.semantics { testTag = "mode_menu" },
            ) {
                val buildTitle = stringResource(R.string.chat_mode_build)
                val planTitle = stringResource(R.string.chat_mode_plan)
                ModeRow(
                    title = buildTitle,
                    body = stringResource(R.string.chat_mode_build_body),
                    active = !plan,
                    onClick = {
                        open = false
                        onPickMode(OpenCodeRepository.AGENT_BUILD)
                    },
                    modifier = Modifier.semantics {
                        testTag = "mode_pick_build"
                        contentDescription = buildTitle
                    },
                )
                ModeRow(
                    title = planTitle,
                    body = stringResource(R.string.chat_mode_plan_body),
                    active = plan,
                    onClick = {
                        open = false
                        onPickMode(OpenCodeRepository.AGENT_PLAN)
                    },
                    modifier = Modifier.semantics {
                        testTag = "mode_pick_plan"
                        contentDescription = planTitle
                    },
                )
            }
        }
    }
}

/** One mode row: name, one-line truth, gold check on the active one. */
@Composable
private fun ModeRow(
    title: String,
    body: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    DropdownMenuItem(
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = chat.muted)
            }
        },
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
        modifier = Modifier.widthIn(min = 220.dp).then(modifier),
    )
}

/**
 * The one-glance status, as a glass orb (owner's v9 brief): collapsed it is a
 * small circle holding only the coloured dot - gold while the agent works,
 * green when ready, amber while the runtime starts, red when something is
 * down. A tap (or any status change) expands it into a capsule with the dot
 * on the left and the status word beside it, and ~2.6 s later it settles back
 * to the orb on its own. Finite animations only: one delay, one contentSize
 * tween, one visibility tween per change - nothing loops. The banners and the
 * busy bar below stay the detailed record; this is a summary, never the only
 * signal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusOrb(
    busy: Boolean,
    availability: AgentAvailability,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    val text: String
    val color: Color
    when {
        busy -> {
            text = stringResource(R.string.chat_status_working)
            color = MaterialTheme.colorScheme.primary
        }
        availability == AgentAvailability.READY -> {
            text = stringResource(R.string.chat_status_ready)
            color = chat.success
        }
        availability == AgentAvailability.RUNTIME_STARTING -> {
            text = stringResource(R.string.chat_status_starting)
            color = chat.attention
        }
        else -> {
            text = stringResource(R.string.chat_status_attention)
            color = MaterialTheme.colorScheme.error
        }
    }
    var pulse by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    // Re-runs when the status text changes OR the user taps: expand, hold,
    // settle back. A change mid-hold restarts the effect (the old one is
    // cancelled), so the capsule simply stays open showing the new word.
    LaunchedEffect(text, pulse) {
        expanded = true
        delay(2600)
        expanded = false
    }
    Surface(
        onClick = { pulse++ },
        color = chat.toolContainer,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, chat.toolBorder),
        modifier = modifier.semantics {
            testTag = "chat_status_pill"
            contentDescription = text
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(36.dp)
                .animateContentSize(animationSpec = tween(240))
                .padding(horizontal = 13.dp),
        ) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(160)) + expandHorizontally(tween(240)),
                exit = fadeOut(tween(140)) + shrinkHorizontally(tween(220)),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

// ---- status ----------------------------------------------------------------

/**
 * Everything that can make the agent unusable or merely busy, in one place, in a
 * fixed order of seriousness: a local problem outranks a provider problem, which
 * outranks "working".
 */
@Composable
private fun StatusArea(
    state: OpenCodeRepository.UiState,
    runtime: RuntimeSummary,
    availability: AgentAvailability,
    onDismissBanner: () -> Unit,
) {
    // A turn can die server-side while the agent itself stays perfectly healthy:
    // run 34142798659's live turn failed inside upstream's own prompt_async with
    // ProviderModelNotFoundError, and the conversation showed nothing at all - no
    // banner (availability was READY, and the banner below only renders for a
    // non-READY state) and no card (TurnErrorCard only renders a message-level
    // error). The session-level error is not an availability state, so it must not
    // wait for one to become visible: upstream's own name and message, on screen.
    state.turnError?.let { error ->
        TurnErrorCard(
            error = error,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    if (availability != AgentAvailability.READY) {
        val raw = state.error.ifBlank { state.turnError?.let { e -> "${e.name}: ${e.message}".trim() } ?: "" }
        // Deliberately no `extra`: the supervisor's own detail line carries the
        // loopback bind address once the runtime is healthy ("healthy on
        // <host>:<port>"), and the conversation surface never shows a host, a port
        // or a command line. That detail belongs to Settings > Runtime and to the
        // diagnostics bundle, where a user goes looking for it.
        AvailabilityBanner(
            availability = availability,
            rawDetail = raw,
            extra = "",
            onDismiss = if (state.error.isNotBlank()) onDismissBanner else null,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        return
    }
    val retry = state.retry
    if (retry != null) {
        RetryBanner(retry)
        return
    }
    if (state.busy) {
        // v9.31 (owner): the working bar names the task actually running.
        // Source of truth: the CURRENT turn's latest `todowrite` call -
        // messages after the last user prompt, newest tool part first -
        // parsed by the same TodoParser the tool card uses. Nothing from
        // older turns (a finished task list must not haunt the next one).
        // v9.32 (owner): while a NEW todowrite call is still streaming, its
        // input is non-blank but PARTIAL JSON - parsing it yields an empty
        // list, and picking merely the newest non-blank input made the bar
        // fall back to "The agent is working" for seconds between phases.
        // So: walk ALL of the turn's todo calls newest-first and keep the
        // first one whose PARSE is non-empty - the previous complete list
        // stays on the bar until the new one has fully arrived.
        val todos = remember(state.messages) {
            val msgs = state.messages
            val turnStart = msgs.indexOfLast { it.role == "user" }
            val turn = if (turnStart >= 0) msgs.subList(turnStart + 1, msgs.size) else emptyList()
            turn.asReversed().asSequence()
                .flatMap { m -> m.parts.asReversed().asSequence() }
                .filter { p -> ToolKinds.of(p.tool) == ToolKind.TODO && p.input.isNotBlank() }
                .map { p -> TodoParser.parse(p.input, p.metadata) }
                .firstOrNull { it.isNotEmpty() }
                ?: emptyList()
        }
        BusyBar(todos)
    }
}

/**
 * v9.31 (owner): "turn the working bar into a collapsible TODO panel". While
 * the agent runs with a task list, the bar shows the task in progress and a
 * done-counter; tapping unfolds the full list - done tasks checked off,
 * the current one marked, pending ones waiting. Without todos it is the v8
 * bar, word for word. The task hand-off is a [Crossfade] and the unfold an
 * [animateContentSize] - both FINITE, the standing UI-gate animation rule.
 */
@Composable
private fun BusyBar(todos: List<TodoItem> = emptyList()) {
    val chat = ChatTheme.chat
    val label = stringResource(R.string.chat_working)
    val done = todos.count { it.status == "completed" }
    val current = todos.firstOrNull { it.status == "in_progress" }
        ?: todos.firstOrNull { it.status == "pending" }
    val headline = current?.content?.takeIf { it.isNotBlank() } ?: label
    var expanded by remember { mutableStateOf(false) }

    // v9.32 (owner): a finished phase deserves its moment. When the named task
    // changes and the OLD task is now marked completed, the bar holds
    // "✓ old task" in the success colour for a beat before crossfading to the
    // next task - a FINITE delay inside a LaunchedEffect, not an animation
    // loop, so the gates' test clock stays idle. rememberUpdatedState lets the
    // running effect read the freshest todo list without restarting.
    val latestTodos by rememberUpdatedState(todos)
    var shown by remember { mutableStateOf(headline) }
    var showingDone by remember { mutableStateOf(false) }
    LaunchedEffect(headline) {
        if (shown != headline) {
            val old = shown
            val oldCompleted = old != label &&
                latestTodos.any { it.content == old && it.status == "completed" }
            if (oldCompleted) {
                showingDone = true
                delay(900L)
                showingDone = false
            }
            shown = headline
        }
    }
    val displayText = if (showingDone) "\u2713 $shown" else shown
    Surface(
        color = chat.toolContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, chat.toolBorder),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (todos.isEmpty()) it else it.clickable { expanded = !expanded } }
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .animateContentSize()
                .semantics {
                    testTag = "busy_bar"
                    contentDescription = label
                    if (todos.isNotEmpty()) role = Role.Button
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (showingDone) chat.success else MaterialTheme.colorScheme.primary,
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    Crossfade(targetState = displayText, label = "busy_task") { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelMedium,
                            // The colour is read off the text being drawn (both
                            // sides compose during the crossfade): the ✓ frame is
                            // success-green, every other frame is plain.
                            color = if (text.startsWith("\u2713 ")) chat.success else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (todos.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.chat_todo_count, done, todos.size),
                        style = MonoSmall,
                        color = chat.muted,
                    )
                    Spacer(Modifier.width(6.dp))
                    // Decorative fold marker; the Column row itself carries the
                    // accessible name and the Button role.
                    Text(
                        text = if (expanded) "\u25B4" else "\u25BE",
                        style = MonoSmall,
                        color = chat.muted,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded && todos.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(Modifier.semantics { testTag = "busy_todo_list" }) {
                    for (todo in todos) {
                        Row(
                            modifier = Modifier.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val glyph = when (todo.status) {
                                "completed" -> "\u2713"
                                "in_progress" -> "\u25CF"
                                "cancelled" -> "\u00D7"
                                else -> "\u25CB"
                            }
                            Text(
                                text = glyph,
                                style = MonoSmall,
                                color = when (todo.status) {
                                    "completed" -> chat.success
                                    "in_progress" -> MaterialTheme.colorScheme.primary
                                    else -> chat.muted
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = todo.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = when (todo.status) {
                                    "in_progress" -> MaterialTheme.colorScheme.onSurface
                                    else -> chat.muted
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Upstream retries on its own and says so; the UI shows that instead of a spinner. */
@Composable
private fun RetryBanner(retry: Transcript.RetryInfo) {
    val chat = ChatTheme.chat
    val headline = stringResource(R.string.chat_retrying, retry.attempt)
    val detail = retry.message
    Surface(
        color = chat.attentionContainer,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics { testTag = "retry_banner" },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = chat.attention,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = headline,
                    style = MaterialTheme.typography.labelMedium,
                    color = chat.onAttentionContainer,
                )
            }
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(text = detail, style = MonoSmall, color = chat.onAttentionContainer)
            }
        }
    }
}

// ---- transcript ------------------------------------------------------------

/**
 * The scrolling transcript, with pin-to-bottom while streaming and a "N new"
 * affordance once the user has scrolled away.
 */
@Composable
private fun TranscriptPane(
    messages: List<Transcript.Message>,
    busy: Boolean,
    now: Long,
    listState: LazyListState,
    onRetry: (() -> Unit)?,
    onUndo: (() -> Unit)?,
    /** v9.32 (owner): the pending question renders as the transcript's tail. */
    question: Transcript.Question? = null,
    onQuestionSubmit: (String, List<List<String>>) -> Unit = { _, _ -> },
    onQuestionSkip: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val emptyTitle = stringResource(R.string.chat_empty_title)
    val emptyBody = stringResource(R.string.chat_empty_body)
    val jumpLabel = stringResource(R.string.chat_jump_to_latest)

    val scope = rememberCoroutineScope()
    var pinned by rememberSaveable { mutableStateOf(true) }
    var seenCount by remember { mutableStateOf(messages.size) }
    val count = messages.size

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 || (last != null && last.index >= info.totalItemsCount - 1)
        }
    }

    // Streaming text grows the LAST part of the LAST message without changing the
    // message count, so the scroll key includes it: the view follows the reply as
    // it arrives, and only while the user is at the bottom.
    val streamKey = remember(messages, question) {
        val last = messages.lastOrNull()
        val tail = last?.parts?.lastOrNull()
        // v9.32: the inline question card is part of the key, so a question
        // arriving scrolls into view exactly like a streaming part would.
        "${last?.id ?: ""}|${last?.parts?.size ?: 0}|${tail?.text?.length ?: 0}|${tail?.status ?: ""}|q=${question?.id ?: ""}"
    }

    LaunchedEffect(atBottom) {
        pinned = atBottom
        if (atBottom) seenCount = count
    }

    LaunchedEffect(streamKey, count) {
        // The question card is one extra trailing item beyond the messages.
        val lastIndex = count - 1 + (if (question != null) 1 else 0)
        if (count > 0 && pinned) listState.scrollToItem(lastIndex)
    }

    val unseen = if (pinned) 0 else (count - seenCount).coerceAtLeast(0)

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTag = TAG_TRANSCRIPT },
            // Phase 10 polish: a little more air between turns and at the edges.
            // Bounded (14->16 dp padding, 14->18 dp between turns) so the number of
            // rows a viewport composes stays in the same range U1 asserts on.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (messages.isEmpty()) {
                // v9.1: an intentional idle state - a golden glyph and the two
                // lines, centred in the viewport - instead of copy stranded at
                // the top of a dark void. Same strings as before (the driver
                // reads the title as a chat needle).
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillParentMaxHeight(0.68f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.widthIn(max = 420.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(goldAccentBrush(), RoundedCornerShape(15.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.project_glyph),
                                    style = MonoSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = emptyTitle,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = emptyBody,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ChatTheme.chat.muted,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            itemsIndexed(items = messages, key = { _, m -> m.id }, contentType = { _, m -> m.role }) { index, message ->
                val isLast = message.id == messages.lastOrNull()?.id
                // v8 beauty pass: consecutive assistant messages from the same
                // model read as ONE turn - the model name appears once above the
                // group and the token/cost footer once below it (summed over the
                // group, so the number is the turn's real total).
                val prev = if (index > 0) messages[index - 1] else null
                val next = if (index < messages.size - 1) messages[index + 1] else null
                val assistant = message.role != "user"
                val headerShown = !assistant || prev == null || prev.role == "user" ||
                    prev.providerID != message.providerID || prev.modelID != message.modelID
                val footerShown = !assistant || next == null || next.role == "user" ||
                    next.providerID != message.providerID || next.modelID != message.modelID
                var tokensIn = -1L
                var tokensOut = -1L
                var cost = -1.0
                if (assistant && footerShown) {
                    tokensIn = 0L; tokensOut = 0L; cost = 0.0
                    var i = index
                    while (i >= 0) {
                        val m = messages[i]
                        if (m.role == "user" || m.providerID != message.providerID || m.modelID != message.modelID) break
                        tokensIn += m.tokensInput
                        tokensOut += m.tokensOutput
                        cost += m.cost
                        i--
                    }
                }
                // v9.1 (owner): under the COMPLETE response, the true wall-clock
                // span of the turn - from the moment the user's message was
                // created to the moment the final assistant message finished.
                // Only at the real end of a turn (the next row is the user or
                // nothing), and only once the response is complete.
                var elapsedMs = -1L
                if (assistant && footerShown && (next == null || next.role == "user") && message.completedMs > 0L) {
                    var j = index
                    while (j >= 0 && messages[j].role != "user") j--
                    val askedMs = if (j >= 0) messages[j].createdMs else 0L
                    if (askedMs in 1 until message.completedMs) elapsedMs = message.completedMs - askedMs
                }
                // v9.1: the conversation lives in a centred column with a
                // comfortable maximum width - unchanged on phones, intentional
                // on wide screens.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    MessageRow(
                        message = message,
                        now = now,
                        streaming = busy && isLast,
                        onRetry = if (isLast && message.role != "user") onRetry else null,
                        onUndo = if (isLast && message.role != "user") onUndo else null,
                        // v9.4 (owner's bug 3): Copy/Like/Dislike live under EVERY
                        // completed agent turn (once per turn, at the group's last
                        // message), not only the newest one. Retry/Undo stay on
                        // the newest turn - they act on "the last turn" by
                        // definition. v9.5 (owner): the row waits for the server's
                        // OWN completion stamp (`time.completed` -> message.completed),
                        // not the busy flag - the busy signal can lag around a
                        // turn's edges, and the buttons must never precede the
                        // finished response.
                        showActions = assistant && footerShown &&
                            (next == null || next.role == "user") &&
                            message.completed && !(busy && isLast),
                        showHeader = headerShown,
                        showFooter = footerShown,
                        groupTokensIn = tokensIn,
                        groupTokensOut = tokensOut,
                        groupCost = cost,
                        groupElapsedMs = elapsedMs,
                        modifier = Modifier.widthIn(max = 760.dp),
                    )
                }
            }
            // v9.32 (owner): the pending question is the transcript's tail -
            // a small card right below whatever the agent just said (for a
            // plan, directly below the plan), so the whole context stays
            // readable above the ask.
            if (question != null) {
                item(key = "question_${question.id}") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        QuestionAsk(
                            question = question,
                            onSubmit = onQuestionSubmit,
                            onReject = onQuestionSkip,
                            modifier = Modifier.widthIn(max = 760.dp),
                        )
                    }
                }
            }
        }

        if (unseen > 0) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            ) {
                TextButton(
                    onClick = {
                        pinned = true
                        seenCount = count
                        scope.launch { if (count > 0) listState.scrollToItem(count - 1) }
                    },
                    modifier = Modifier
                        .height(40.dp)
                        .semantics {
                            testTag = "jump_to_latest"
                            contentDescription = jumpLabel
                        },
                ) {
                    Text(
                        text = stringResource(R.string.chat_new_messages, unseen),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

// ---- blocking asks ---------------------------------------------------------

/**
 * Permissions the agent is waiting on, presented as a bottom sheet rather than
 * a card buried in the scroll.
 *
 * v9.32 (owner): QUESTIONS left this sheet - they render inline in the
 * transcript now (see [TranscriptPane]), because a question is part of the
 * conversation and must sit below the text it asks about. A permission is an
 * interruption, so the sheet remains its home.
 *
 * Upstream blocks the turn until a permission is answered, so the sheet is not
 * dismissible: tapping the scrim or the back button does nothing, and the only
 * ways out are the answers the agent is waiting for (allow once / always allow /
 * reject). This is a restyle of the same upstream mechanism — the three
 * permission replies are unchanged, and OpenCode's permission model is fully
 * preserved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskArea(
    asks: List<Transcript.Prompt>,
    onPermissionReply: (String, String) -> Unit,
) {
    if (asks.isEmpty()) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { /* non-dismissible: the turn is blocked until answered */ },
        sheetState = sheetState,
    ) {
        // Scrollable on purpose: when several permission asks stack up, every
        // answer the agent is waiting for must stay reachable by scrolling the
        // sheet. The sheet provides bounded height, so a verticalScroll column
        // here cannot hit the unbounded-height crash a lazy list would.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (ask in asks) {
                PermissionAsk(prompt = ask, onReply = onPermissionReply)
            }
        }
    }
}

// ---- composer --------------------------------------------------------------

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    busy: Boolean,
    canSend: Boolean,
    canRedo: Boolean,
    onRedo: () -> Unit,
) {
    // v8 redesign, after the reference: one rounded container holds the whole
    // composer - a ghost attach button on the left, a borderless input in the
    // middle, and a filled gold send circle (or the Stop pill while a turn
    // runs) on the right. Same tags, same enable/disable rules as before: the
    // container is the restyle, not the contract.
    val chat = ChatTheme.chat
    val blockedHint = stringResource(R.string.availability_composer_blocked)
    val sendLabel = stringResource(R.string.chat_send)
    val attachLabel = stringResource(R.string.chat_attach)
    // v9.1: the composer is the primary element at the bottom - a floating
    // capsule in the same centred column as the conversation, with clear air
    // between it and the tab strip below.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        ) {
            if (!canSend) {
                Text(
                    text = blockedHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.muted,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp),
                )
            }
            if (canRedo) {
                // v9: an icon, not a word (owner's request for the turn
                // actions). The label survives as the accessibility name.
                val redoLabel = stringResource(R.string.chat_redo_turn)
                IconButton(
                    onClick = onRedo,
                    modifier = Modifier.size(40.dp).semantics {
                        testTag = "redo_turn"
                        contentDescription = redoLabel
                    },
                ) {
                    Icon(
                        imageVector = RedoGlyph,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp).clearAndSetSemantics { },
                    )
                }
            }
            Surface(
                color = chat.toolContainer,
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, chat.toolBorder),
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    // The bottom paddings below centre each control against the
                    // single-line field (the field's intrinsic height is 56dp:
                    // 8+20=28, 7+21=28, 6+22=28) while the Bottom alignment keeps
                    // them anchored when the field grows to six lines. v8 fix
                    // round: the owner saw the old 2/4dp riding visibly low.
                    IconButton(
                        onClick = onAttach,
                        enabled = canSend,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .size(40.dp)
                            .semantics {
                                testTag = TAG_COMPOSER_ATTACH
                                contentDescription = attachLabel
                            },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = chat.muted,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics { testTag = TAG_COMPOSER_INPUT },
                        placeholder = { Text(stringResource(R.string.chat_composer_placeholder)) },
                        maxLines = 6,
                        enabled = canSend,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    // M3-expressive micro-interactions: a physical press (the send
                    // circle shrinks under the finger, spring release) and a haptic
                    // tick on the two actions that commit something. Finite
                    // animations only - the gates' test clock must stay idle.
                    val haptic = LocalHapticFeedback.current
                    if (busy) {
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStop()
                            },
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .height(44.dp)
                                .semantics { testTag = TAG_COMPOSER_STOP },
                        ) {
                            Text(
                                text = stringResource(R.string.chat_stop),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        val sendEnabled = canSend && draft.isNotBlank()
                        val sendInteraction = remember { MutableInteractionSource() }
                        val sendPressed by sendInteraction.collectIsPressedAsState()
                        val sendScale by animateFloatAsState(
                            targetValue = if (sendPressed) 0.86f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "sendScale",
                        )
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSend()
                            },
                            enabled = sendEnabled,
                            interactionSource = sendInteraction,
                            modifier = Modifier
                                .padding(bottom = 7.dp)
                                .size(42.dp)
                                .graphicsLayer {
                                    scaleX = sendScale
                                    scaleY = sendScale
                                }
                                .background(
                                    brush = if (sendEnabled) {
                                        goldAccentBrush()
                                    } else {
                                        SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                    },
                                    shape = CircleShape,
                                )
                                .semantics { testTag = TAG_COMPOSER_SEND },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = sendLabel,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
