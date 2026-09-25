package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.client.Transcript
import ai.opencode.android.client.UiError
import ai.opencode.android.ui.common.AvailabilityBanner
import ai.opencode.android.ui.common.EmptyState
import ai.opencode.android.ui.common.ProjectTab
import ai.opencode.android.ui.common.ProjectTabs
import ai.opencode.android.ui.common.RuntimeSummary
import ai.opencode.android.ui.common.StatusPill
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    onOpenFiles: () -> Unit = {},
    /** v7 redesign: the Changes and Terminal tabs under the project header. */
    onOpenChanges: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    /** v4 item 3: pick one of the starred models without a trip to Settings. */
    onPickModel: (String, String) -> Unit = { _, _ -> },
    onPermissionReply: (String, String) -> Unit,
    onQuestionSubmit: (String, List<List<String>>) -> Unit,
    onQuestionSkip: (String) -> Unit,
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
            .semantics { testTag = "chat_screen" },
    ) {
        // v8 redesign: the reference header - glyph tile + project identity on
        // the left, the one-glance status chip and a single hamburger menu on
        // the right. The owner called the old four-icon toolbar clutter: Files
        // duplicated the bottom tab and Sessions duplicated the project list,
        // so both are gone and the remaining actions live inside `chat_menu`.
        ChatHeader(
            projectLabel = projectLabel,
            sessionTitle = sessionTitle,
            projectsDescription = projectsDescription,
            busy = state.busy,
            availability = availability,
            onOpenProjects = onOpenProjects,
            onNewSession = onNewSession,
            onOpenSettings = onOpenSettings,
        )

        // The quick switch sits directly under the bar: it is a property of the
        // conversation the user is looking at (which model answers), not a setting.
        ModelQuickSwitch(
            current = state.model,
            starred = state.starredModels,
            onPick = onPickModel,
            onOpenSettings = onOpenSettings,
        )

        StatusArea(state = state, runtime = runtime, availability = availability, onDismissBanner = onDismissBanner)

        TranscriptPane(
            messages = messages,
            busy = state.busy,
            now = now,
            listState = listState,
            onRetry = if (state.canRetryTurn) onRetry else null,
            onUndo = if (state.canRetryTurn) onUndo else null,
            modifier = Modifier.weight(1f),
        )

        AskArea(
            asks = state.pendingAsks,
            questions = state.pendingQuestions,
            onPermissionReply = onPermissionReply,
            onQuestionSubmit = onQuestionSubmit,
            onQuestionSkip = onQuestionSkip,
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
 * The v8 chat header, after the reference: a rounded code-glyph tile and the
 * project's name lead (tapping them opens the project list, as the old title
 * did), the session's title runs underneath, and the right side carries the
 * status chip plus the four toolbar actions the driver and the gates navigate
 * by. Pure layout: every tag, label and callback is the v7 set.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChatHeader(
    projectLabel: String,
    sessionTitle: String,
    projectsDescription: String,
    busy: Boolean,
    availability: AgentAvailability,
    onOpenProjects: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val chat = ChatTheme.chat
    val newLabel = stringResource(R.string.chat_new_conversation)
    val settingsLabel = stringResource(R.string.chat_open_settings)
    val menuLabel = stringResource(R.string.chat_open_menu)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onOpenProjects)
                        .semantics {
                            role = Role.Button
                            contentDescription = projectsDescription
                        }
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(12.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.project_glyph),
                            style = MonoSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = projectLabel,
                            style = MaterialTheme.typography.titleMedium,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The one-glance status, honestly mapped: gold while the agent
                // works, green when everything is ready, the ask colour when
                // something needs the user. The banners and the busy bar below
                // stay the detailed record - this chip is a summary, never the
                // only signal. It sits OUTSIDE the clickable identity block on
                // purpose: a merged clickable would swallow its test tag.
                val chip = when {
                    busy -> stringResource(R.string.chat_status_working) to MaterialTheme.colorScheme.primary
                    availability == AgentAvailability.READY ->
                        stringResource(R.string.chat_status_ready) to chat.success
                    else -> stringResource(R.string.chat_status_attention) to chat.attention
                }
                StatusPill(
                    text = chip.first,
                    color = chip.second,
                    modifier = Modifier.semantics { testTag = "chat_status_pill" },
                )
                Spacer(Modifier.weight(1f))
                // v8 iteration 3 (owner): the ghost toolbar was clutter - Files
                // duplicated the bottom tab and Sessions duplicated the project
                // list - so the header keeps ONE hamburger button and the two
                // real actions live in its menu, same tags as before.
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(40.dp).semantics { testTag = "chat_menu" },
                    ) {
                        Icon(Icons.Filled.Menu, contentDescription = menuLabel, tint = chat.muted)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
    if (state.busy) BusyBar()
}

@Composable
private fun BusyBar() {
    // v8: a floating rounded card in the reference's voice ("* Agent is
    // working"), not a full-width strip - same tag, same label, same signal.
    val chat = ChatTheme.chat
    val label = stringResource(R.string.chat_working)
    Surface(
        color = chat.toolContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, chat.toolBorder),
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics {
                    testTag = "busy_bar"
                    contentDescription = label
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
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
    val streamKey = remember(messages) {
        val last = messages.lastOrNull()
        val tail = last?.parts?.lastOrNull()
        "${last?.id ?: ""}|${last?.parts?.size ?: 0}|${tail?.text?.length ?: 0}|${tail?.status ?: ""}"
    }

    LaunchedEffect(atBottom) {
        pinned = atBottom
        if (atBottom) seenCount = count
    }

    LaunchedEffect(streamKey, count) {
        if (count > 0 && pinned) listState.scrollToItem(count - 1)
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
                item(key = "empty") { EmptyState(title = emptyTitle, body = emptyBody) }
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
                MessageRow(
                    message = message,
                    now = now,
                    streaming = busy && isLast,
                    onRetry = if (isLast && message.role != "user") onRetry else null,
                    onUndo = if (isLast && message.role != "user") onUndo else null,
                    showHeader = headerShown,
                    showFooter = footerShown,
                    groupTokensIn = tokensIn,
                    groupTokensOut = tokensOut,
                    groupCost = cost,
                )
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
 * Permissions and questions the agent is waiting on, presented as a bottom
 * sheet rather than a card buried in the scroll.
 *
 * Upstream blocks the turn until one of these is answered, so the sheet is not
 * dismissible: tapping the scrim or the back button does nothing, and the only
 * ways out are the answers the agent is waiting for (allow once / always allow /
 * reject, or submit / skip for a question). This is a restyle of the same
 * upstream mechanism — the three permission replies and the question reply shape
 * are unchanged, and OpenCode's permission model is fully preserved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskArea(
    asks: List<Transcript.Prompt>,
    questions: List<Transcript.Question>,
    onPermissionReply: (String, String) -> Unit,
    onQuestionSubmit: (String, List<List<String>>) -> Unit,
    onQuestionSkip: (String) -> Unit,
) {
    if (asks.isEmpty() && questions.isEmpty()) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { /* non-dismissible: the turn is blocked until answered */ },
        sheetState = sheetState,
    ) {
        // Scrollable on purpose: when a permission ask and a question stack up
        // (or a question carries a long option list + custom field), every answer
        // the agent is waiting for must stay reachable by scrolling the sheet.
        // The sheet provides bounded height, so a verticalScroll column here cannot
        // hit the unbounded-height crash a lazy list would.
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
            for (question in questions) {
                QuestionAsk(
                    question = question,
                    onSubmit = onQuestionSubmit,
                    onReject = onQuestionSkip,
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
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
            if (!canSend) {
                Text(
                    text = blockedHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.muted,
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp),
                )
            }
            if (canRedo) {
                TextButton(
                    onClick = onRedo,
                    modifier = Modifier.height(40.dp).semantics { testTag = "redo_turn" },
                ) {
                    Text(stringResource(R.string.chat_redo_turn), style = MaterialTheme.typography.labelMedium)
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
                                    color = if (sendEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
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
