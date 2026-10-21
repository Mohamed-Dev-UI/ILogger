package com.mohamed.dev.ui.ilogger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.ilogger.logging.Task
import com.mohamed.dev.ui.ilogger.logging.TaskActions
import com.mohamed.dev.ui.ilogger.logging.TaskPriority
import com.mohamed.dev.ui.ilogger.ui.DemoController
import com.mohamed.dev.ui.ilogger.ui.components.CodeBlock
import com.mohamed.dev.ui.ilogger.ui.components.Pill
import com.mohamed.dev.ui.ilogger.ui.components.PropertyRow
import com.mohamed.dev.ui.ilogger.ui.components.ScreenIntro
import com.mohamed.dev.ui.ilogger.ui.components.SectionCard
import com.mohamed.dev.ui.ilogger.ui.components.prettyEventJson
import io.ilogger.core.LogMasking

@Composable
fun ProtectionScreen(controller: DemoController) {
    val tasks by controller.tasks.collectAsState()
    val events by controller.feed.events.collectAsState()
    val busy by controller.busy.collectAsState()

    val sample = tasks.firstOrNull() ?: Task(
        id = 1,
        title = "Example task",
        priority = TaskPriority.HIGH,
        privateNote = DemoController.PROTECTION_NOTE,
    )
    val rawNote = sample.privateNote
    val fingerprint = LogMasking.hash(rawNote)
    val archiveEvent = events.firstOrNull { it.action == TaskActions.ARCHIVED }
    val emittedFingerprint = archiveEvent?.metadata?.get("noteFingerprint")?.toString()
    val emittedMask = archiveEvent?.metadata?.get("noteMasked")?.toString()
    val rawLeaked = archiveEvent?.let { prettyEventJson(it).contains(rawNote) } ?: false
    var revealRaw by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenIntro(
                "Sensitive data protection",
                "Protection is compile-time first. @LogSensitive is a contract; selection requires an explicit " +
                    "policy (MASK or HASH), and suspicious property names are refused even without an annotation.",
            )
        }
        item {
            SectionCard(
                title = "The value under contract",
                subtitle = if (tasks.isEmpty()) "Seed sample — the action below persists it first." else "First task on the board.",
            ) {
                PropertyRow("Task", "#${sample.id} · ${sample.title}")
                PropertyRow(
                    "Raw privateNote",
                    if (revealRaw) rawNote else "•".repeat(rawNote.length.coerceAtMost(24)) + " (${rawNote.length} chars)",
                    monospace = true,
                )
                TextButton(onClick = { revealRaw = !revealRaw }) {
                    Text(if (revealRaw) "Hide raw value" else "Reveal raw value (demo only)")
                }
                PropertyRow("SHA-256 prefix", fingerprint, monospace = true)
                PropertyRow("Fingerprint length", LogMasking.HASH_LENGTH.toString())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("raw value = in entity only", MaterialTheme.colorScheme.tertiary)
                    Pill("sinks see projections", MaterialTheme.colorScheme.primary)
                }
                Text(
                    "The hash is deterministic: the same note always produces the same fingerprint, so events " +
                        "remain correlatable without exposing the value. Masking is a fixed \"***\".",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard(
                title = "Emit the protected event",
                subtitle = "task.archived@v2 selects the same value with both HASH and MASK, plus actor/context metadata.",
            ) {
                Button(
                    onClick = { controller.emitProtectedSample() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) { Text("Archive + emit protected audit event") }
                if (archiveEvent == null) {
                    Text(
                        "No task.archived event yet — run the action above.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PropertyRow("noteFingerprint", emittedFingerprint, monospace = true)
                    PropertyRow("noteMasked", emittedMask, monospace = true)
                    PropertyRow("noteHash == computed", (fingerprint == emittedFingerprint).toString())
                    PropertyRow("raw note in event JSON", rawLeaked.toString(), monospace = true)
                    PropertyRow("schemaId", archiveEvent.schemaId, monospace = true)
                    PropertyRow("category", archiveEvent.category, monospace = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("HASH", MaterialTheme.colorScheme.primary)
                        Pill("MASK", MaterialTheme.colorScheme.secondary)
                        Pill("schema v2", MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
        item {
            SectionCard(
                title = "The compile-time contract",
                subtitle = "A sensitive value cannot be selected by accident; the default policy is DENY.",
            ) {
                CodeBlock(
                    title = "Protection.kt",
                    code = """
data class Task(
    val id: Long,
    val title: String,
    @LogSensitive(reason = "Private notes may contain personal data")
    val privateNote: String = "",
)

@Log(
    action = TaskActions.ARCHIVED,
    schemaVersion = 2,
    category = "audit",
    metadata = [
        LogMeta("task.privateNote", name = "noteFingerprint", sensitive = SensitiveHandling.HASH),
        LogMeta("task.privateNote", name = "noteMasked", sensitive = SensitiveHandling.MASK),
        LogMeta("actor.type"),
        LogMeta("actor.name"),
    ],
)
suspend fun archive(task: Task): Boolean
""".trimIndent(),
                )
            }
        }
        item {
            SectionCard(
                title = "Policies",
                subtitle = "DENY is the default; selecting a sensitive value without a policy is a compile error.",
            ) {
                PolicyRow("DENY", "Compile error", "Strongest privacy; absence is visible at compile time")
                PolicyRow("MASK", "\"***\"", "Presence/absence only; zero runtime cost")
                PolicyRow("HASH", "24 hex chars", "Correlate identity without reversing; one SHA-256 per field")
                PolicyRow("OMIT", "not selectable", "An omitted selection is a contradiction — behaves as DENY in v1")
                Text(
                    "Also refused by name, even without annotations: password, passwd, pwd, token, secret, apiKey, " +
                        "privateKey, credential(s), sessionKey, refreshToken, accessToken, authorization, cookie.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard(
                title = "Never logged, not even masked",
                subtitle = "@LogExclude rejects metadata, entity-id and change selections at compile time.",
            ) {
                CodeBlock(
                    title = "excluded.kt",
                    code = """
data class Payment(
    val id: Long,
    @LogExclude(reason = "PCI: never reaches any sink")
    val pan: String,
)

// Compile diagnostic for a selection attempt:
//   'pan' is marked @LogExclude and cannot be selected.
//   Fix: remove it from metadata/changes, or log a derived, non-sensitive value.
""".trimIndent(),
                )
            }
        }
        item {
            SectionCard(
                title = "Defense in depth",
                subtitle = "Why this survives sink misconfiguration.",
            ) {
                PropertyRow("Compile time", "Selection sites are validated; sensitive names are refused")
                PropertyRow("Event build", "Metrics/changes contain only log-safe primitives")
                PropertyRow("Sink time", "Codecs serialize what the event already contains — they cannot undo masking")
                PropertyRow("Field redaction", "Hash/mask happens before the event leaves the wrapper")
            }
        }
    }
}

@Composable
private fun PolicyRow(policy: String, output: String, note: String) {
    val color = when (policy) {
        "DENY" -> MaterialTheme.colorScheme.error
        "MASK" -> MaterialTheme.colorScheme.primary
        "HASH" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(56.dp)) {
            Pill(policy, color)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                output,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = color,
            )
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
