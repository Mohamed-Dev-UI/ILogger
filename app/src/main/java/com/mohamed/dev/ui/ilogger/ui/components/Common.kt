package com.mohamed.dev.ui.ilogger.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import io.ilogger.core.Outcome
import io.ilogger.core.sink.DefaultTextEventCodec
import io.ilogger.serialization.JsonEventCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val prettyJson = Json { prettyPrint = true }

/** Compact JSONL payload pretty-printed for the inspector. */
fun prettyEventJson(event: LogEvent): String = runCatching {
    prettyJson.encodeToString(JsonElement.serializer(), JsonEventCodec.toJsonElement(event))
}.getOrElse { JsonEventCodec.encode(event) }

fun prettyRawJson(raw: String): String = runCatching {
    prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw))
}.getOrElse { raw }

fun oneLine(event: LogEvent): String = DefaultTextEventCodec.encode(event)

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val dateTimeFormat = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss.SSS")

fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormat)

fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dateTimeFormat)

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

@Composable
fun ScreenIntro(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.10f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (filled) color.copy(alpha = 0.14f) else Color.Transparent,
        border = if (filled) null else BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.AUDIT -> MaterialTheme.colorScheme.primary
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    LogLevel.INFO -> MaterialTheme.colorScheme.secondary
    LogLevel.DEBUG, LogLevel.TRACE -> MaterialTheme.colorScheme.outline
}

@Composable
fun outcomeColor(outcome: Outcome): Color = when (outcome) {
    Outcome.SUCCESS -> MaterialTheme.colorScheme.secondary
    Outcome.START -> MaterialTheme.colorScheme.tertiary
    Outcome.FAILURE -> MaterialTheme.colorScheme.error
}

@Composable
fun LevelPill(level: LogLevel) = Pill(level.name, levelColor(level))

@Composable
fun OutcomePill(outcome: Outcome) = Pill(outcome.name, outcomeColor(outcome))

@Composable
fun PropertyRow(label: String, value: String?, monospace: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(112.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value ?: "—",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

@Composable
fun CodeBlock(
    code: String,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title ?: "kotlin",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                ) {
                    Text(if (copied) "Copied" else "Copy", style = MaterialTheme.typography.labelMedium)
                }
            }
            SelectionContainer {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
fun <T> PickerField(
    label: String,
    value: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    optionLabel(value),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("▾", style = MaterialTheme.typography.bodySmall)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option), style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EventCard(
    event: LogEvent,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelPill(event.level)
                if (event.outcome != Outcome.SUCCESS) {
                    Spacer(Modifier.width(4.dp))
                    OutcomePill(event.outcome)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    formatTime(event.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                event.schemaId,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            val attribution = buildString {
                event.actor?.let { append("${it.type.name.lowercase()}:").append(it) }
                event.entity?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                    event.entityId?.let { id -> append("(").append(id).append(")") }
                }
                event.source?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
            }
            if (attribution.isNotEmpty()) {
                Text(
                    attribution,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            event.correlationId?.let {
                Text(
                    "corr=$it" + (event.causationId?.let { cause -> " · cause=$cause" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (event.metadata.isNotEmpty()) {
                Text(
                    event.metadata.entries.joinToString { (key, value) -> "$key=$value" },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            event.failure?.let { failure ->
                Text(
                    "failure: ${failure.errorType.substringAfterLast('.')} — ${failure.message ?: "no message"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun EventDetailDialog(event: LogEvent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.schemaId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LevelPill(event.level)
                    Spacer(Modifier.width(4.dp))
                    OutcomePill(event.outcome)
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatDateTime(event.timestampMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                PropertyRow("Event id", event.id, monospace = true)
                PropertyRow("Action", event.action, monospace = true)
                PropertyRow("Schema id", event.schemaId, monospace = true)
                PropertyRow("Entity", event.entity)
                PropertyRow("Entity id", event.entityId, monospace = true)
                PropertyRow("Actor", event.actor?.let { "${it.type.name.lowercase()} · ${it.id ?: "no id"}${it.name?.let { n -> " · $n" } ?: ""}" })
                PropertyRow("Source", event.source, monospace = true)
                PropertyRow("Category", event.category)
                PropertyRow("Correlation", event.correlationId, monospace = true)
                PropertyRow("Causation", event.causationId, monospace = true)

                if (event.metadata.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Metadata", style = MaterialTheme.typography.labelLarge)
                    event.metadata.forEach { (key, value) -> PropertyRow(key, value?.toString(), true) }
                }

                if (event.changes.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Changes (SUCCESS only)", style = MaterialTheme.typography.labelLarge)
                    event.changes.forEach { change ->
                        PropertyRow(change.field, "${change.before} → ${change.after}")
                    }
                }

                event.failure?.let { failure ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Failure", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    PropertyRow("Type", failure.errorType, monospace = true)
                    PropertyRow("Message", failure.message)
                    PropertyRow("Root cause", failure.rootCauseType, monospace = true)
                    PropertyRow("Stack", failure.stackSummary.joinToString("\n"), monospace = true)
                }

                Spacer(Modifier.height(8.dp))
                CodeBlock(title = "event.json", code = prettyEventJson(event))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
