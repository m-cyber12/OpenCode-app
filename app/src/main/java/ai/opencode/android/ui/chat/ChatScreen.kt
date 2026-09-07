package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.client.OpenCodeRepository
import ai.opencode.android.client.Transcript
import ai.opencode.android.client.UiError
import ai.opencode.android.ui.common.AppTopBar
import ai.opencode.android.ui.common.AvailabilityBanner
import ai.opencode.android.ui.common.EmptyState
import ai.opencode.android.ui.common.RuntimeSummary
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
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
        AppTopBar(
            title = projectLabel,
            subtitle = sessionTitle,
            onTitleClick = onOpenProjects,
            titleDescription = projectsDescription,
            actions = {
                val conversationsLabel = stringResource(R.string.chat_conversations)
                val newLabel = stringResource(R.string.chat_new_conversation)
                val settingsLabel = stringResource(R.string.chat_open_settings)
                IconButton(
                    onClick = onOpenSessions,
                    modifier = Modifier.semantics { testTag = "open_sessions" },
                ) {
                    Icon(Icons.Filled.Menu, contentDescription = conversationsLabel)
                }
                IconButton(
                    onClick = onNewSession,
                    modifier = Modifier.semantics { testTag = "new_session" },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = newLabel)
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.semantics { testTag = "open_settings" },
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = settingsLabel)
                }
            },
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
    val chat = ChatTheme.chat
    val label = stringResource(R.string.chat_working)
    Surface(color = chat.toolContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics {
                    testTag = "busy_bar"
                    contentDescription = label
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = chat.success,
            )
            Spacer(Modifier.width(10.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = chat.muted)
        }
    }
}

/** Upstream retries on its own and says so; the UI shows that instead of a spinner. */
@Composable
private fun RetryBanner(retry: Transcript.RetryInfo) {
    val chat = ChatTheme.chat
    val headline = stringResource(R.string.chat_retrying, retry.attempt)
    val detail = retry.message
    Surface(color = chat.attentionContainer, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
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
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (messages.isEmpty()) {
                item(key = "empty") { EmptyState(title = emptyTitle, body = emptyBody) }
            }
            items(items = messages, key = { it.id }, contentType = { it.role }) { message ->
                val isLast = message.id == messages.lastOrNull()?.id
                MessageRow(
                    message = message,
                    now = now,
                    streaming = busy && isLast,
                    onRetry = if (isLast && message.role != "user") onRetry else null,
                    onUndo = if (isLast && message.role != "user") onUndo else null,
                )
            }
        }

        if (unseen > 0) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
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
    val chat = ChatTheme.chat
    val blockedHint = stringResource(R.string.availability_composer_blocked)
    val sendLabel = stringResource(R.string.chat_send)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            if (!canSend) {
                Text(
                    text = blockedHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = chat.muted,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
            }
            if (canRedo) {
                TextButton(
                    onClick = onRedo,
                    modifier = Modifier.padding(start = 8.dp).height(40.dp).semantics { testTag = "redo_turn" },
                ) {
                    Text(stringResource(R.string.chat_redo_turn), style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextButton(
                    onClick = onAttach,
                    enabled = canSend,
                    modifier = Modifier.height(48.dp).semantics { testTag = TAG_COMPOSER_ATTACH },
                ) {
                    Text(stringResource(R.string.chat_attach), style = MaterialTheme.typography.labelMedium)
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .semantics { testTag = TAG_COMPOSER_INPUT },
                    placeholder = { Text(stringResource(R.string.chat_composer_placeholder)) },
                    label = { Text(stringResource(R.string.chat_composer_label)) },
                    maxLines = 6,
                    enabled = canSend,
                    shape = MaterialTheme.shapes.medium,
                )
                Spacer(Modifier.width(6.dp))
                if (busy) {
                    TextButton(
                        onClick = onStop,
                        modifier = Modifier.height(48.dp).semantics { testTag = TAG_COMPOSER_STOP },
                    ) {
                        Text(stringResource(R.string.chat_stop), style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = canSend && draft.isNotBlank(),
                        modifier = Modifier.size(48.dp).semantics { testTag = TAG_COMPOSER_SEND },
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = sendLabel)
                    }
                }
            }
        }
    }
}
