package ai.opencode.android.ui.common

import ai.opencode.android.R
import ai.opencode.android.client.AgentAvailability
import ai.opencode.android.ui.theme.ChatTheme
import ai.opencode.android.ui.theme.MonoSmall
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Widgets shared by the four screens.
 *
 * Every one of them is a pure function of the values it is handed: no singleton,
 * no repository, no runtime. That is the property `check-ui-purity.py` guards and
 * what lets the Compose UI gates render any state on demand.
 */

/** App bar built from stable primitives (no experimental Material 3 API). */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    titleDescription: String = "",
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
                        .then(
                            if (onTitleClick != null && titleDescription.isNotEmpty()) {
                                Modifier.semantics {
                                    role = Role.Button
                                    contentDescription = titleDescription
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (onTitleClick != null) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).clearAndSetSemantics { },
                        )
                    }
                }
                actions()
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** A titled card with an optional explanation line; the common section shape. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    body: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            if (body.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** Label + value line. The value is selectable: users copy versions and paths. */
@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                style = if (mono) MonoSmall else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Small coloured label used for statuses (tool state, session state, MCP). */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50), modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

/**
 * Raw upstream text behind a disclosure.
 *
 * The Phase 6 rule is that a human-readable headline never replaces what the
 * server said: every error surface pairs copy from resources with the verbatim
 * text, one tap away, selectable, unedited.
 */
@Composable
fun DetailDisclosure(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }) {
            Text(stringResource(if (open) R.string.action_hide_details else R.string.action_details))
        }
        if (open) {
            SelectionContainer(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                Text(
                    text = text,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The degraded-state banner. One widget, one vocabulary: the headline says what
 * kind of "not working" this is, the body says what the user can do, and the raw
 * server text is behind [DetailDisclosure].
 */
@Composable
fun AvailabilityBanner(
    availability: AgentAvailability,
    modifier: Modifier = Modifier,
    rawDetail: String = "",
    extra: String = "",
    onDismiss: (() -> Unit)? = null,
) {
    if (availability == AgentAvailability.READY) return
    val chat = ChatTheme.chat
    val providerSide = availability == AgentAvailability.PROVIDER_UNREACHABLE ||
        availability == AgentAvailability.PROVIDER_AUTH ||
        availability == AgentAvailability.PROVIDER_OTHER ||
        availability == AgentAvailability.ABORTED
    val container = if (providerSide) chat.attentionContainer else MaterialTheme.colorScheme.errorContainer
    val onContainer = if (providerSide) chat.onAttentionContainer else MaterialTheme.colorScheme.onErrorContainer
    val accent = if (providerSide) chat.attention else MaterialTheme.colorScheme.error
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // The accent bar is the only colour-only signal, so it is never the
            // only one: the headline says what kind of problem this is in words.
            Surface(color = accent, modifier = Modifier.width(4.dp).fillMaxHeight()) {}
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 8.dp)
                    // Tagged so the degraded-state gate can assert on the banner that
                    // is actually on screen rather than on a string in a resource file.
                    .semantics { testTag = "availability_banner" },
            ) {
                Text(
                    text = availabilityHeadline(availability),
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = availabilityBody(availability),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
                if (extra.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(text = extra, style = MonoSmall, color = onContainer)
                }
                if (rawDetail.isNotBlank()) DetailDisclosure(rawDetail)
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.Top)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_dismiss),
                        tint = onContainer,
                    )
                }
            }
        }
    }
}

// ---- formatting ------------------------------------------------------------

/** Human-readable copy for an [AgentAvailability]. Kept in one place on purpose. */
@Composable
fun availabilityHeadline(a: AgentAvailability): String = stringResource(
    when (a) {
        AgentAvailability.READY -> R.string.availability_ready
        AgentAvailability.RUNTIME_STARTING -> R.string.availability_runtime_starting
        AgentAvailability.RUNTIME_UNAVAILABLE -> R.string.availability_runtime_unavailable
        AgentAvailability.SERVER_UNREACHABLE -> R.string.availability_server_unreachable
        AgentAvailability.SERVER_AUTH -> R.string.availability_server_auth
        AgentAvailability.REQUEST_REJECTED -> R.string.availability_request_rejected
        AgentAvailability.PROVIDER_UNREACHABLE -> R.string.availability_provider_unreachable
        AgentAvailability.PROVIDER_AUTH -> R.string.availability_provider_auth
        AgentAvailability.PROVIDER_OTHER -> R.string.availability_provider_other
        AgentAvailability.ABORTED -> R.string.availability_aborted
        AgentAvailability.UNKNOWN -> R.string.availability_unknown
    },
)

@Composable
fun availabilityBody(a: AgentAvailability): String = stringResource(
    when (a) {
        AgentAvailability.READY -> R.string.availability_ready
        AgentAvailability.RUNTIME_STARTING -> R.string.availability_runtime_starting_body
        AgentAvailability.RUNTIME_UNAVAILABLE -> R.string.availability_runtime_unavailable_body
        AgentAvailability.SERVER_UNREACHABLE -> R.string.availability_server_unreachable_body
        AgentAvailability.SERVER_AUTH -> R.string.availability_server_auth_body
        AgentAvailability.REQUEST_REJECTED -> R.string.availability_request_rejected_body
        AgentAvailability.PROVIDER_UNREACHABLE -> R.string.availability_provider_unreachable_body
        AgentAvailability.PROVIDER_AUTH -> R.string.availability_provider_auth_body
        AgentAvailability.PROVIDER_OTHER -> R.string.availability_provider_other_body
        AgentAvailability.ABORTED -> R.string.availability_aborted_body
        AgentAvailability.UNKNOWN -> R.string.availability_unknown_body
    },
)

/**
 * Relative time, the way a conversation list reads it. [now] is a parameter so a
 * UI test can pin it instead of racing the clock.
 */
@Composable
fun relativeTimeLabel(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return ""
    val seconds = (now - ms) / 1000L
    return when {
        seconds < 45L -> stringResource(R.string.time_just_now)
        seconds < 3600L -> {
            val m = (seconds / 60L).toInt()
            if (m <= 1) stringResource(R.string.time_one_minute_ago) else stringResource(R.string.time_minutes_ago, m)
        }
        seconds < 86_400L -> {
            val h = (seconds / 3600L).toInt()
            if (h <= 1) stringResource(R.string.time_one_hour_ago) else stringResource(R.string.time_hours_ago, h)
        }
        seconds < 604_800L -> {
            val d = (seconds / 86_400L).toInt()
            if (d <= 1) stringResource(R.string.time_one_day_ago) else stringResource(R.string.time_days_ago, d)
        }
        else -> absoluteTimeLabel(ms)
    }
}

@Composable
fun absoluteTimeLabel(ms: Long): String {
    if (ms <= 0L) return ""
    return remember(ms) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

/** Pure size formatting (attachments, tool output). */
fun formatBytes(bytes: Long): String = when {
    bytes < 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/** Pure cost formatting: upstream reports cost in USD as a double. */
fun formatCost(cost: Double): String =
    if (cost <= 0.0) "" else String.format(Locale.US, "\$%.4f", cost)

/** Collapse whitespace in server text so a one-line label stays one line. */
fun oneLine(text: String): String = text.replace('\n', ' ').replace('\r', ' ').trim()
