package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.client.Transcript
import ai.opencode.android.client.UiError
import ai.opencode.android.ui.common.AvailabilityBanner
import ai.opencode.android.ui.common.rememberLoadingPulse
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.graphics.Path
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
    /**
     * v9.42 (owner): the message the user already sent while the agent is
     * still starting. It is IN the conversation - their own words, on screen -
     * and reaches the model the moment the app is ready. Submission has to
     * feel like a fully initialized app, not a disabled one.
     */
    pendingMessage: String? = null,
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
    // v9.41 (owner item 15): the conversation drawer switches/renames/deletes
    // sessions itself - the same callbacks the full-page panel gets, wired to
    // the same repository calls. Defaults keep every existing call site (and
    // gate) compiling untouched.
    onSelectSession: (String) -> Unit = {},
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    onDeleteSession: (String) -> Unit = {},
    // v9.41 (owner item 16): the `+` opens a three-button menu first; Camera
    // and Pictures get their own launchers, Files keeps the old `onAttach`.
    onAttachCamera: () -> Unit = {},
    onAttachPictures: () -> Unit = {},
    // v9.41 (owner items 3+6): one quiet line while a send waits behind the
    // agent boot or a GitHub refresh. Empty string = no line at all.
    sendWaitLabel: String = "",
    // v9.41 (owner item 20): null keeps the built-in rule ("first chat of the
    // project"); AppRoot overrides it with the install-first-launch decision.
    emptyChatSubtitle: Boolean? = null,
    /** v4 item 3: pick one of the starred models without a trip to Settings. */
    onPickModel: (String, String) -> Unit = { _, _ -> },
    /** Build/plan: which built-in agent the next prompt runs as. */
    onPickMode: (String) -> Unit = {},
    /** The reasoning-variant id for the next prompt; "" = model default. */
    onPickThinking: (String) -> Unit = {},
    /** v9.19 token saver: upstream /compact - summarize the history server-side. */
    onPermissionReply: (String, String) -> Unit,
    onQuestionSubmit: (String, List<List<String>>) -> Unit,
    onQuestionSkip: (String) -> Unit,
    /** v9.31 (owner): GitHub projects carry the mark in the menu tile too. */
    githubProject: Boolean = false,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissBanner: () -> Unit,
    /**
     * v9.35 (owner): the live preview moved into the chat - a console card
     * directly below the TODO panel (port visible, Reload/Close, the door
     * while closed) and a full-screen morph overlay above everything.
     */
    preview: PreviewUi = PreviewUi(),
    livePorts: List<Int> = emptyList(),
    onOpenPreviewPort: (Int) -> Unit = {},
    onPreviewClose: () -> Unit = {},
    onPreviewReload: () -> Unit = {},
    /** v9.38 (owner): stop THIS project's preview server from the overlay. */
    onPreviewStop: () -> Unit = {},
    onPreviewBrowser: () -> Unit = {},
    onPreviewCapture: (android.graphics.Bitmap) -> Unit = {},
    onPreviewConsole: (String) -> Unit = {},
    /** v9.35.4: tapping the live console bar re-opens the preview morph. */
    onPreviewShow: () -> Unit = {},
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
    listState: LazyListState = rememberLazyListState(),
) {
    val messages = state.messages
    val sessionTitle = state.selectedInfo?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_untitled)
    val projectLabel = projectName.ifEmpty { stringResource(R.string.projects_title) }
    val projectsDescription = stringResource(R.string.chat_open_projects)

    // v9.35.6 (owner): the circle morph is gone - the preview page arrives
    // with one simple finite fade. The console only hides while the overlay
    // owns the screen.

    // v9.41 (owner item 15): the hamburger opens a ChatGPT-style side drawer -
    // it covers part of the screen with the current project's conversations in
    // it; it is not a popup. State lives here (not in the header) because the
    // header only holds the button that opens it.
    var drawerOpen by remember { mutableStateOf(false) }

    // v9.35: root is a Box now - the conversation column underneath, the
    // preview page overlay on top of EVERYTHING (header, tabs included).
    Box(
        modifier = modifier
            .fillMaxSize()
            // v9: the whole conversation floats over true black that settles
            // into a faint golden bloom at the base (a static brush - the
            // glow never animates). Header, composer and tab bar are
            // transparent so the one backdrop runs edge to edge.
            .background(goldenBackdrop())
            .semantics { testTag = "chat_screen" },
    ) {
    Column(Modifier.fillMaxSize()) {
        // v9 premium pass, after the Gemini reference: ONE header row - the
        // hamburger leads, the model capsule sits beside it, the status orb
        // holds the far right. The project/session identity moved INSIDE the
        // hamburger menu (glass card at its very top), so the row above the
        // conversation carries exactly three quiet controls and nothing else.
        // v9.41 (owner item 22): every Zen model belongs to the quick switch's
        // "Free Models" group - free needs no starring to be reachable. The
        // variants lookup answers for ANY listed model from the same catalog
        // snapshot the repository already holds.
        val freeQuick = remember(state.providers) {
            state.providers?.entries
                ?.firstOrNull { it.id == OpenCodeApi.ZEN_PROVIDER_ID }
                ?.models
                ?.map { m -> QuickModel(OpenCodeApi.ModelRef(OpenCodeApi.ZEN_PROVIDER_ID, m.id), m.name, m.variants) }
                ?: emptyList()
        }
        fun variantsOf(pid: String, mid: String): List<String> =
            state.providers?.entries?.firstOrNull { it.id == pid }?.models
                ?.firstOrNull { it.id == mid }?.variants ?: emptyList()
        ChatHeader(
            onOpenMenu = { drawerOpen = true },
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
            modelsLoading = state.providers == null,
            freeModels = freeQuick,
            variantsOf = ::variantsOf,
            onPickWithVariant = { pid, mid, v ->
                onPickModel(pid, mid)
                onPickThinking(v)
            },
        )

        StatusArea(state = state, runtime = runtime, availability = availability, onDismissBanner = onDismissBanner)

        // v9.41 (owner items 3+6): the "waiting" line - shown while a send
        // waits behind the agent boot or a repo refresh. One finite, quiet
        // label; the composer stays usable.
        if (sendWaitLabel.isNotEmpty()) {
            Text(
                text = sendWaitLabel,
                style = MaterialTheme.typography.labelMedium,
                color = ChatTheme.chat.muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 2.dp)
                    .semantics { testTag = "send_wait_note" },
            )
        }

        // v9.35 (owner): the preview console sits DIRECTLY below the TODO
        // panel - a clone of its rounded container, asking "See What You've
        // Made" (v9.37); the door while nothing is live. While the overlay
        // owns the screen the console hides.
        LivePreviewConsole(
            preview = preview,
            livePorts = livePorts,
            onOpenPort = onOpenPreviewPort,
            onShow = onPreviewShow,
            modifier = Modifier
                .alpha(if (preview.phase == PreviewState.CLOSED) 1f else 0f),
        )

        // v9.32 (owner): questions render INLINE in the chat - a small card at
        // the tail of the transcript, right below whatever the agent just said.
        // For a plan that means directly below the plan text, so the user can
        // read the whole plan and then decide (the old sheet pinned the card
        // over the composer and HID the plan it asked about). Permissions stay
        // in the AskArea sheet - they are interruptions, not conversation.
        //
        // v9.34 (owner: "when the user approves, the model sees the answer and
        // CONTINUES working - don't stop it, don't send a prompt"). The plan
        // approval is upstream's own `plan_exit` tool now (experimental plan
        // mode): the plan agent calls it, plan_exit asks the Yes/No question
        // rendered here, and a "Yes" makes the SERVER append a synthetic
        // build-agent message so the same turn keeps running with build
        // permissions. The app's only job left is cosmetic: flip the visible
        // mode chip to Build so the next prompt the USER types also builds.
        val pendingQuestion = state.pendingQuestions.firstOrNull()
        TranscriptPane(
            messages = messages,
            // v9.35 (owner): the typed subtitle belongs ONLY to the first chat
            // of a freshly created project - later chats go straight to tips.
            firstChatOfProject = emptyChatSubtitle ?: (state.sessions.size <= 1),
            busy = state.busy,
            now = now,
            listState = listState,
            onRetry = if (state.canRetryTurn) onRetry else null,
            onUndo = if (state.canRetryTurn) onUndo else null,
            question = pendingQuestion,
            onQuestionSubmit = { id, answers ->
                onQuestionSubmit(id, answers)
                val q = pendingQuestion
                if (state.agentMode == OpenCodeRepository.AGENT_PLAN &&
                    q != null && q.id == id && isPlanExitQuestion(q) &&
                    answers.any { group -> group.any { it.equals("Yes", ignoreCase = true) } }
                ) {
                    onPickMode(OpenCodeRepository.AGENT_BUILD)
                }
            },
            onQuestionSkip = onQuestionSkip,
            modifier = Modifier.weight(1f),
        )

        // v9.42 (owner): the user's own message, in the chat, the moment they
        // send it - even though the agent is still starting. It sits directly
        // under the conversation (always composed, never below an empty-state
        // spacer), becomes the real prompt when the runtime is ready, and says
        // so in one quiet line instead of pretending the send did not happen.
        if (!pendingMessage.isNullOrBlank()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                PendingSendRow(
                    text = pendingMessage,
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

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
            onAttachCamera = onAttachCamera,
            onAttachPictures = onAttachPictures,
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
    // v9.41 (owner item 15): the conversation drawer, above everything.
    ChatConversationDrawer(
        open = drawerOpen,
        onDismiss = { drawerOpen = false },
        projectLabel = projectLabel,
        projectsDescription = projectsDescription,
        githubProject = githubProject,
        sessionTitle = sessionTitle,
        sessions = state.sessions,
        selectedSession = state.selectedSession,
        transcript = state.transcript,
        now = now,
        ready = runtime.ready,
        onOpenProjects = { drawerOpen = false; onOpenProjects() },
        onNewSession = { drawerOpen = false; onNewSession() },
        onOpenAddons = { drawerOpen = false; onOpenAddons() },
        onOpenProviders = { drawerOpen = false; onOpenProviders() },
        onOpenSettings = { drawerOpen = false; onOpenSettings() },
        onSelect = { id -> drawerOpen = false; onSelectSession(id) },
        onRename = onRenameSession,
        onDelete = onDeleteSession,
    )

    // v9.35: the morph overlay composes last, above the whole column.
    if (preview.phase != PreviewState.CLOSED) {
        ChatPreviewOverlay(
            preview = preview,
            onClose = onPreviewClose,
            onBrowser = onPreviewBrowser,
            onReload = onPreviewReload,
            onCapture = onPreviewCapture,
            onConsole = onPreviewConsole,
            onStop = onPreviewStop,
        )
    }
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
    busy: Boolean,
    /** v9.41 (owner item 15): opens the conversation drawer. */
    onOpenMenu: () -> Unit,
    availability: AgentAvailability,
    model: OpenCodeApi.ModelRef?,
    starred: List<OpenCodeApi.ModelRef>,
    thinkingVariants: List<String>,
    thinkingVariant: String,
    agentMode: String,
    onPickModel: (String, String) -> Unit,
    onPickMode: (String) -> Unit,
    onPickThinking: (String) -> Unit,
    // v9.41 (owner item 22): the quick switch also lists the FREE models (Zen)
    // and knows each listed model's thinking variants for its inline picker.
    freeModels: List<QuickModel> = emptyList(),
    variantsOf: (String, String) -> List<String> = { _, _ -> emptyList() },
    onPickWithVariant: (String, String, String) -> Unit = { p, m, _ -> onPickModel(p, m) },
    onOpenProjects: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAddons: () -> Unit,
    /** v9.42 (owner): the catalog has not arrived yet - the capsule says so. */
    modelsLoading: Boolean = false,
) {
    val chat = ChatTheme.chat
    val newLabel = stringResource(R.string.chat_new_conversation)
    val settingsLabel = stringResource(R.string.chat_open_settings)
    val providersLabel = stringResource(R.string.chat_open_providers)
    val addonsLabel = stringResource(R.string.chat_open_addons)
    val menuLabel = stringResource(R.string.chat_open_menu)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v9.41 (owner item 15): the hamburger no longer pops a dropdown - it
        // opens the conversation drawer hosted by the screen root. The identity
        // card and every action that lived in this menu moved into the drawer,
        // tag for tag. The circle itself is byte-identical (v9.6 size rule).
        Surface(
            onClick = onOpenMenu,
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
        // v9.41 (owner item 9): one gap everywhere - the old row mixed 6dp and
        // 8dp, which is what made the pieces read as uneven.
        Spacer(Modifier.width(8.dp))
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
            
                freeModels = freeModels,
                variantsOf = variantsOf,
                onPickWithVariant = onPickWithVariant,
                modelsLoading = modelsLoading,
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
    // v9.42 (owner): "the status circle should have an animation to show the
    // user that the app is loading in the background". While the agent boots
    // the dot breathes and a ring expands out of it - the one thing in the
    // header that tells the user work is happening behind the first frame.
    val loading = availability == AgentAvailability.RUNTIME_STARTING
    // Four frames, stepped by the shared pulse; the ring walks outwards while
    // the dot breathes. Bounded by composition - see rememberLoadingPulse.
    val pulseTick = if (loading) rememberLoadingPulse(380) else 0
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
            Box(contentAlignment = Alignment.Center) {
                if (loading) {
                    // The expanding ring: each beat is a wider, fainter circle,
                    // so it reads as one pulse leaving the dot.
                    val step = pulseTick % 4
                    val ringSize = (12 + step * 3).dp
                    Box(
                        Modifier
                            .size(ringSize)
                            .alpha(0.55f - step * 0.11f)
                            .background(color.copy(alpha = 0.5f), CircleShape)
                            .clearAndSetSemantics { },
                    )
                }
                val breath = if (loading) BREATH_FRAMES[pulseTick % BREATH_FRAMES.size] else 1f
                Box(
                    Modifier
                        .size(10.dp)
                        .background(color.copy(alpha = breath), CircleShape)
                        .clearAndSetSemantics { },
                )
            }
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

// v9.42 (owner): the dot's four brightness frames while the agent boots.
private val BREATH_FRAMES = floatArrayOf(0.35f, 0.62f, 1f, 0.62f)

// ---- status ----------------------------------------------------------------

/**
 * v9.42 (owner): the boot state, AS a loading state. No red, no error copy -
 * one quiet line naming what is happening and a dot that moves, because "the
 * app is working in the background" is the message, not "something is wrong".
 * The word stays `Starting the agent` so every surface agrees on it.
 */
@Composable
private fun StartingRow(modifier: Modifier = Modifier) {
    val chat = ChatTheme.chat
    val label = stringResource(R.string.availability_runtime_starting)
    // v9.42: four beats - one dot, two, three, then none - stepped by the
    // shared pulse (bounded by composition, see rememberLoadingPulse).
    val phase = rememberLoadingPulse(420) % 4
    Surface(
        color = chat.toolContainer,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, chat.toolBorder),
        modifier = modifier.fillMaxWidth().semantics { testTag = "chat_loading_row" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = chat.muted,
            )
            Spacer(Modifier.width(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.clearAndSetSemantics { },
            ) {
                for (i in 0 until 3) {
                    val lit = phase < 3 && i <= phase
                    Box(
                        Modifier
                            .size(5.dp)
                            .alpha(if (lit) 1f else 0.28f)
                            .background(
                                if (lit) MaterialTheme.colorScheme.primary else chat.muted,
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

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
    // v9.40 (owner): "the screen becomes too cluttered - a single error
    // message below the user message is sufficient". One rule for the whole
    // area: the failure's verbatim text shows in EXACTLY one place. The
    // failed turn's own row carries it whenever a row exists (MessageRow
    // renders it) - then NOTHING floats and no banner repeats it. An error
    // no row shows (upstream dying before the assistant message exists -
    // ProviderModelNotFoundError) keeps the compact inline card here, since
    // the raw text must stay visible and never hide inside a collapsed
    // disclosure. The availability banner stays for every degraded state
    // (its headline is the human context - runtime down, server silent,
    // starting up) but never restates the turn's text.
    val turnError = state.turnError
    val turnErrorShownByRow = turnError?.let { te ->
        val msgs = state.messages
        val turnStart = msgs.indexOfLast { it.role == "user" }
        val inTurn = if (turnStart >= 0) msgs.subList(turnStart + 1, msgs.size) else msgs
        inTurn.any { m -> m.error?.name == te.name && m.error?.message == te.message }
    } == true
    if (turnError != null && !turnErrorShownByRow) {
        TurnErrorCard(
            error = turnError,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    // v9.38 (owner): "that yellow Stop indicator is unnecessary and
    // misleading - just show the word Stopped under the token count". A user
    // stopping a turn is not a degraded agent: no banner, no card - the tiny
    // footer note on the turn itself is the whole signal.
    // v9.42 (owner): "it looks much more like an error than a loading state"
    // - the red banner is GONE from the boot path. Starting is loading: a
    // quiet neutral row with a moving dot, and the status orb animating.
    // Everything else here is unchanged - a real failure still says so, once.
    if (availability == AgentAvailability.RUNTIME_STARTING) {
        StartingRow(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        return
    }
    if (availability != AgentAvailability.READY && availability != AgentAvailability.ABORTED) {
        if (!turnErrorShownByRow || state.error.isNotBlank()) {
            // Deliberately no `extra`: the supervisor's own detail line carries the
            // loopback bind address once the runtime is healthy ("healthy on
            // <host>:<port>"), and the conversation surface never shows a host, a port
            // or a command line. That detail belongs to Settings > Runtime and to the
            // diagnostics bundle, where a user goes looking for it.
            AvailabilityBanner(
                availability = availability,
                rawDetail = state.error,
                extra = "",
                onDismiss = if (state.error.isNotBlank()) onDismissBanner else null,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
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
    // v9.41 (owner item 10): the pending FALLBACK is gone. Marking the first
    // unfinished item as "the current task" is what pinned the gold dot to
    // item one for the rest of a turn whenever the model forgot to post an
    // in_progress marker - "stays on the first item, does not move". Only a
    // real in_progress gets the headline; otherwise the generic label does.
    val current = todos.firstOrNull { it.status == "in_progress" }
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

/**
 * v9.34: recognise upstream `plan_exit`'s own approval ask (tool/plan.ts at
 * the pinned commit: header "Build Agent", question "... switch to the build
 * agent and start implementing?"). Only THIS question's "Yes" flips the
 * visible mode chip to Build - any other Yes/No the model asks in plan mode
 * must leave the mode alone.
 */
internal fun isPlanExitQuestion(question: Transcript.Question): Boolean =
    question.items.any { item ->
        item.header.equals("Build Agent", ignoreCase = true) ||
            item.question.contains("switch to the build agent", ignoreCase = true)
    }

// ---- transcript ------------------------------------------------------------

/**
 * Test seam for the empty-state typewriter: the deterministic UI gates flip
 * [animate] off so the line renders fully and instantly (a character-by-
 * character coroutine would leave the on-screen text mid-word at assert
 * time). The real app never touches this.
 */
object TypewriterConfig {
    @Volatile
    var animate: Boolean = true
}

/**
 * v9.35 (owner): the line under the empty-chat headline is ALIVE now.
 *
 *  * First chat of a freshly created project: the polished subtitle types in
 *    character by character, stays for 15 seconds ONCE FULLY TYPED (the
 *    typing and deleting time does not count against the hold - the owner
 *    was explicit), then deletes itself the same way.
 *  * Then - and immediately, in every later chat - the tip carousel: one of
 *    the 30 short tips types in, holds for 7.5 seconds fully displayed,
 *    deletes, and the next one follows. The day picks where the carousel
 *    starts so it does not always open on tip one.
 *
 * Deliberately NOT a Compose animation API: a plain coroutine mutating a
 * string state. The repo-wide static check forbids new infinite animation
 * primitives (they spin the test clock forever); a delay-driven loop never
 * touches the animation clock, and the gates switch it off entirely via
 * [TypewriterConfig].
 */
/**
 * Process-static anchor for the empty-chat line (v9.41, owner item 20): the
 * typewriter's phase is a pure function of wall-clock time since app start,
 * so leaving the chat and coming back CONTINUES the line mid-cycle instead of
 * replaying the typing animation from zero.
 */
private val EMPTY_CHAT_ANCHOR_MS = System.currentTimeMillis()

/**
 * v9.35 (owner): the line under the empty-chat headline is ALIVE now.
 *
 *  * First chat of a freshly created project (and, from v9.41 on, only the
 *    first launch after install): the polished subtitle types in character by
 *    character, stays for 15 seconds ONCE FULLY TYPED, then deletes itself.
 *  * Then - and immediately, in every later chat - the tip carousel: one of
 *    the 30 short tips types in, holds for 7.5 seconds fully displayed,
 *    deletes, and the next one follows. The day picks where the carousel
 *    starts so it does not always open on tip one.
 *
 * v9.41 (owner item 20): the cycle itself moved into [EmptyChatCopy.frameAt]
 * - pure and unit-tested - and this composable just samples it against the
 * wall clock. Deliberately NOT a Compose animation API: a delay-driven sampler
 * never touches the animation clock, and the gates switch the whole line off
 * (fully-typed, no motion) via [TypewriterConfig].
 */
@Composable
private fun EmptyChatTypewriter(firstChat: Boolean) {
    val animate = TypewriterConfig.animate
    val dayStart = remember {
        ((java.time.LocalDate.now().toEpochDay() % EmptyChatCopy.TIPS.size).toInt() + EmptyChatCopy.TIPS.size) % EmptyChatCopy.TIPS.size
    }
    var frame by remember { mutableStateOf(EmptyChatCopy.Frame(EmptyChatCopy.SUBTITLE, caret = false)) }
    if (animate) {
        LaunchedEffect(firstChat, dayStart) {
            while (true) {
                frame = EmptyChatCopy.frameAt(
                    elapsedMs = System.currentTimeMillis() - EMPTY_CHAT_ANCHOR_MS,
                    firstChat = firstChat,
                    tipStart = dayStart,
                )
                delay(90L)
            }
        }
    }
    Text(
        text = if (frame.caret) frame.text + "\u258D" else frame.text,
        style = MaterialTheme.typography.bodyMedium,
        color = ChatTheme.chat.muted,
        textAlign = TextAlign.Center,
        // Three lines of air reserved up front, so the glyph and headline do
        // not bounce while the line grows and shrinks underneath them.
        modifier = Modifier
            .heightIn(min = 60.dp)
            .semantics { testTag = "chat_empty_hint" },
    )
}

/**
 * The scrolling transcript, with pin-to-bottom while streaming and a "N new"
 * affordance once the user has scrolled away.
 */
/**
 * v9.42 (owner): a sent message that is still waiting for the agent. Rendered
 * as the user's own bubble - same shape, same side - with one quiet line under
 * it, because "your message is here and will go out" is the honest statement.
 * It is never presented as an answer, and it disappears the moment the real
 * turn starts (the queued text becomes the actual user message).
 */
@Composable
private fun PendingSendRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    val chat = ChatTheme.chat
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .semantics { testTag = "pending_send_row" },
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 620.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.chat_pending_send),
            style = MaterialTheme.typography.labelSmall,
            color = chat.muted,
        )
    }
}

@Composable
private fun TranscriptPane(
    messages: List<Transcript.Message>,
    firstChatOfProject: Boolean,
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
    val jumpLabel = stringResource(R.string.chat_jump_to_latest)

    val scope = rememberCoroutineScope()
    var pinned by rememberSaveable { mutableStateOf(true) }
    var seenCount by remember { mutableStateOf(messages.size) }
    val count = messages.size
    // v9.33: scrolling to the conversation's tail means the BOTTOM of the last
    // item, not its top. scrollToItem(last) aligns the item's TOP with the
    // viewport - mid-stream that "jumped back up" to the start of a long
    // reply every time new text arrived (the owner's report). A large item
    // offset lands at the end instead; the lazy list clamps it to the real
    // bounds. Bounded well below Int overflow in layout arithmetic.
    val tailOffsetPx = 1 shl 20

    // v9.33 (owner): "at the bottom" now means the last item's bottom EDGE is
    // inside the viewport (with a little slack), not merely "the last item is
    // visible". During a long streaming reply the last item fills the whole
    // screen, so the old test stayed true even when the reader had scrolled
    // up inside it - and every delta yanked the view back. Reading mid-reply
    // now unpins; returning to the true bottom re-pins.
    val bottomSlackPx = with(LocalDensity.current) { 48.dp.toPx() }.toInt()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 || (
                last != null && last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + bottomSlackPx
                )
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

    // v9.33 (owner): entering the chat lands on the LATEST messages, always.
    // The old behaviour relied on `pinned` still being true when the initial
    // scroll effect ran - but the pin observer could read "not at bottom"
    // from the first layout (index 0 of a long list) and falsify it first,
    // so navigating back to a long conversation opened at the very top.
    // This one-shot does not depend on that race: first time content exists
    // after entering the screen, jump to the tail and re-arm the pin.
    var openedAtBottom by remember { mutableStateOf(false) }
    LaunchedEffect(count) {
        if (!openedAtBottom && count > 0) {
            openedAtBottom = true
            pinned = true
            seenCount = count
            listState.scrollToItem(count - 1 + (if (question != null) 1 else 0), tailOffsetPx)
        }
    }

    LaunchedEffect(atBottom) {
        pinned = atBottom
        if (atBottom) seenCount = count
    }

    LaunchedEffect(streamKey, count) {
        // The question card is one extra trailing item beyond the messages.
        val lastIndex = count - 1 + (if (question != null) 1 else 0)
        if (count > 0 && pinned) listState.scrollToItem(lastIndex, tailOffsetPx)
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
                // v9.1: an intentional idle state - a golden glyph and copy
                // centred in the viewport. v9.35 (owner): the headline is one
                // of the owner's 30 messages, rotating once per day, and under
                // it a typewriter line - the polished subtitle in a brand-new
                // project's first chat (held 15s once fully typed), then the
                // tip carousel. The driver never depended on this copy: it
                // navigates by the `chat_screen`/`composer_input` tags.
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
                                text = EmptyChatCopy.headlineFor(
                                    remember { java.time.LocalDate.now().toEpochDay() },
                                ),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.semantics { testTag = "chat_empty_headline" },
                            )
                            Spacer(Modifier.height(8.dp))
                            EmptyChatTypewriter(firstChat = firstChatOfProject)
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
                        scope.launch {
                            if (count > 0) {
                                listState.scrollToItem(count - 1 + (if (question != null) 1 else 0), tailOffsetPx)
                            }
                        }
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


// ---- conversation drawer (v9.41, owner item 15) ----------------------------

/**
 * The ChatGPT-style side panel the hamburger opens: it slides in from the
 * start, covers a stable fraction of the screen (not a popup), and holds the
 * CURRENT project's conversations - each with its three-dot menu (Delete /
 * Rename, in [SessionRow]) - plus the identity card and the actions that used
 * to live in the old dropdown, tag for tag: `open_projects` (the identity
 * card), `new_session`, `open_addons`, `open_providers`, `open_settings`. Entering/leaving is finite (190/160 ms), matching the
 * sheet rule; the scrim closes it, the system back closes it.
 */
@Composable
private fun ChatConversationDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    projectLabel: String,
    sessionTitle: String,
    projectsDescription: String,
    githubProject: Boolean,
    sessions: List<ai.opencode.android.client.OpenCodeApi.SessionInfo>,
    selectedSession: String,
    transcript: Transcript.Snapshot,
    now: Long,
    ready: Boolean,
    onOpenProjects: () -> Unit,
    onNewSession: () -> Unit,
    onOpenAddons: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally(tween(190)) { full -> -(full * 0.30f).toInt() } + fadeIn(tween(160)),
        exit = slideOutHorizontally(tween(160)) { full -> -(full * 0.30f).toInt() } + fadeOut(tween(130)),
    ) {
        val chat = ChatTheme.chat
        Box(Modifier.fillMaxSize().semantics { testTag = "conversation_drawer" }) {
            // Scrim: taps outside close the panel (ChatGPT behavior). No
            // elevation games - a plain dim layer over the conversation.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onDismiss),
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp),
                border = BorderStroke(1.dp, chat.toolBorder),
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.66f),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                ) {
                    // The identity card, moved verbatim from the old dropdown
                    // header of the menu - same glass, same gold tile, one tap
                    // = the project list.
                    Surface(
                        onClick = onOpenProjects,
                        color = chat.toolContainer,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, chat.toolBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { testTag = "open_projects"; contentDescription = projectsDescription },
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
                            Column(Modifier.fillMaxWidth()) {
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
                    Spacer(Modifier.height(6.dp))
                    DrawerAction(
                        tag = "new_session",
                        label = stringResource(R.string.chat_new_conversation),
                        icon = Icons.Filled.Add,
                        onClick = onNewSession,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.sessions_title),
                        style = MaterialTheme.typography.labelSmall,
                        color = chat.muted,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                    if (!ready) {
                        Text(
                            text = stringResource(R.string.drawer_sessions_starting),
                            style = MaterialTheme.typography.bodySmall,
                            color = chat.muted,
                            modifier = Modifier.padding(8.dp).semantics { testTag = "drawer_sessions_pending" },
                        )
                    } else if (sessions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.sessions_empty_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = chat.muted,
                            modifier = Modifier.padding(8.dp),
                        )
                    } else {
                        SessionList(
                            sessions = sessions,
                            selectedSession = selectedSession,
                            transcript = transcript,
                            now = now,
                            onSelect = onSelect,
                            onRename = onRename,
                            onDelete = onDelete,
                            listTag = "drawer_session_list",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(chat.toolBorder),
                    )
                    Spacer(Modifier.height(6.dp))
                    DrawerAction(
                        tag = "open_providers",
                        label = stringResource(R.string.chat_open_providers),
                        icon = Icons.Filled.Lock,
                        onClick = onOpenProviders,
                    )
                    DrawerAction(
                        tag = "open_addons",
                        label = stringResource(R.string.chat_open_addons),
                        icon = Icons.Filled.Build,
                        onClick = onOpenAddons,
                    )
                    DrawerAction(
                        tag = "open_settings",
                        label = stringResource(R.string.chat_open_settings),
                        icon = Icons.Filled.Settings,
                        onClick = onOpenSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerAction(
    tag: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val chat = ChatTheme.chat
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().semantics { testTag = tag; role = Role.Button },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = chat.muted, modifier = Modifier.size(18.dp).clearAndSetSemantics { })
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AttachTile(
    kind: AttachGlyphKind,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    val chat = ChatTheme.chat
    // v9.41 (owner item 16): the Claude-style square icon buttons - one tile
    // per source, side by side, before any system picker is asked for. The
    // glyphs are app-drawn (the StopGlyph precedent): the camera/pictures/
    // files icons are not in material-icons-core, and the extended artifact
    // stays off the classpath on purpose.
    Surface(
        onClick = onClick,
        color = chat.toolContainer,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, chat.toolBorder),
        modifier = Modifier
            .size(56.dp)
            .semantics {
                testTag = tag
                role = Role.Button
                contentDescription = label
            },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            AttachGlyph(kind, Modifier.size(24.dp).clearAndSetSemantics { })
        }
    }
}

private enum class AttachGlyphKind { Camera, Pictures, Files }

@Composable
private fun AttachGlyph(kind: AttachGlyphKind, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        when (kind) {
            AttachGlyphKind.Camera -> {
                drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(1f, h * 0.30f),
                    size = androidx.compose.ui.geometry.Size(w - 2f, h * 0.56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke, stroke),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
                // the little viewfinder bump
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.30f),
                    androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.16f), strokeWidth = stroke)
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.34f, h * 0.16f),
                    androidx.compose.ui.geometry.Offset(w * 0.62f, h * 0.16f), strokeWidth = stroke)
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.62f, h * 0.16f),
                    androidx.compose.ui.geometry.Offset(w * 0.62f, h * 0.30f), strokeWidth = stroke)
                drawCircle(tint, radius = w * 0.16f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.58f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
            }
            AttachGlyphKind.Pictures -> {
                drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(1f, 2f),
                    size = androidx.compose.ui.geometry.Size(w - 2f, h - 4f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke, stroke),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
                val mountain = Path().apply {
                    moveTo(w * 0.14f, h * 0.78f)
                    lineTo(w * 0.40f, h * 0.46f)
                    lineTo(w * 0.60f, h * 0.68f)
                    lineTo(w * 0.74f, h * 0.54f)
                    lineTo(w * 0.88f, h * 0.78f)
                }
                drawPath(mountain, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
                drawCircle(tint, radius = w * 0.09f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.30f))
            }
            AttachGlyphKind.Files -> {
                val fold = w * 0.28f
                val doc = Path().apply {
                    moveTo(2f, 2f)
                    lineTo(w - fold, 2f)
                    lineTo(w - 2f, 2f + fold)
                    lineTo(w - 2f, h - 2f)
                    lineTo(2f, h - 2f)
                    close()
                }
                drawPath(doc, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
                val corner = Path().apply {
                    moveTo(w - fold, 2f)
                    lineTo(w - fold, 2f + fold)
                    lineTo(w - 2f, 2f + fold)
                }
                drawPath(corner, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.55f),
                    androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.55f), strokeWidth = stroke)
                drawLine(tint, androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.74f),
                    androidx.compose.ui.geometry.Offset(w * 0.60f, h * 0.74f), strokeWidth = stroke)
            }
        }
    }
}


// ---- blocking asks ---------------------------------------------------------

/**
 * Permissions the agent is waiting on.
 *
 * v9.32 (owner): QUESTIONS left this area - they render inline in the
 * transcript now (see [TranscriptPane]), because a question is part of the
 * conversation and must sit below the text it asks about.
 *
 * v9.41 (owner item 18): the ModalBottomSheet is GONE. A separate sheet
 * window drifted downward with the IME, and being non-dismissible it could
 * hold an orphaned transparent frame until a restart - the exact stuck card
 * of the owner's screenshot. The ask is a plain, opaque inline surface
 * between transcript and composer now: it cannot drift, nothing transparent
 * remains, and it shows ONE ask at a time with a quiet count of the rest
 * (upstream blocks the turn on the first answer anyway, so queueing them is
 * exactly what the agent is waiting for). The three permission replies are
 * unchanged; OpenCode's permission model is fully preserved.
 */
@Composable
private fun AskArea(
    asks: List<Transcript.Prompt>,
    onPermissionReply: (String, String) -> Unit,
) {
    if (asks.isEmpty()) return
    val chat = ChatTheme.chat
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, chat.attention),
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { testTag = "ask_area" },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            PermissionAsk(prompt = asks.first(), onReply = onPermissionReply)
            if (asks.size > 1) {
                Text(
                    text = stringResource(R.string.chat_asks_waiting, asks.size - 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.muted,
                    modifier = Modifier
                        .padding(start = 12.dp, bottom = 6.dp)
                        .semantics { testTag = "ask_more_waiting" },
                )
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
    onAttachCamera: () -> Unit = {},
    onAttachPictures: () -> Unit = {},
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
                    // v9.41 (owner item 16): the `+` no longer jumps straight
                    // into the system picker. It opens a minimal menu first:
                    // three square tiles - Camera, Pictures, Files - side by
                    // side; only the tap on a tile opens that source.
                    var attachOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { attachOpen = true },
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
                        MaterialTheme(
                            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(18.dp)),
                        ) {
                            DropdownMenu(expanded = attachOpen, onDismissRequest = { attachOpen = false }) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AttachTile(
                                        kind = AttachGlyphKind.Camera,
                                        label = stringResource(R.string.chat_attach_camera),
                                        tag = "attach_camera",
                                    ) {
                                        attachOpen = false
                                        onAttachCamera()
                                    }
                                    AttachTile(
                                        kind = AttachGlyphKind.Pictures,
                                        label = stringResource(R.string.chat_attach_pictures),
                                        tag = "attach_pictures",
                                    ) {
                                        attachOpen = false
                                        onAttachPictures()
                                    }
                                    AttachTile(
                                        kind = AttachGlyphKind.Files,
                                        label = stringResource(R.string.chat_attach_files),
                                        tag = "attach_files",
                                    ) {
                                        attachOpen = false
                                        onAttach()
                                    }
                                }
                            }
                        }
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
