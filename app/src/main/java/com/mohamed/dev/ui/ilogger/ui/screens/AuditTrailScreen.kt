package com.mohamed.dev.ui.ilogger.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.ilogger.ui.AuditTrailController
import com.mohamed.dev.ui.ilogger.ui.components.CodeBlock
import com.mohamed.dev.ui.ilogger.ui.components.EmptyState
import com.mohamed.dev.ui.ilogger.ui.components.LevelPill
import com.mohamed.dev.ui.ilogger.ui.components.PropertyRow
import com.mohamed.dev.ui.ilogger.ui.components.ScreenIntro
import com.mohamed.dev.ui.ilogger.ui.components.SectionCard
import com.mohamed.dev.ui.ilogger.ui.components.StatTile
import com.mohamed.dev.ui.ilogger.ui.components.formatBytes
import com.mohamed.dev.ui.ilogger.ui.components.formatTime
import com.mohamed.dev.ui.ilogger.ui.components.prettyRawJson
import io.ilogger.annotations.LogLevel
import io.ilogger.core.Outcome
import io.ilogger.room.RoomLogEntity

@Composable
fun AuditTrailScreen(controller: AuditTrailController) {
    val state by controller.state.collectAsState()
    var selected by remember { mutableStateOf<RoomLogEntity?>(null) }

    // The controller may have been built before the database's lifecycle
    // events drained; re-read every time this screen becomes visible.
    LaunchedEffect(controller) { controller.refresh() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenIntro(
                "Room audit trail",
                "The durable side. Every event goes through RoomLogTarget into the library-owned ilogger_events " +
                    "table, including room.database.created/opened lifecycle events queued before the DAO existed.",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("rows", state.rows.size.toString(), Modifier.weight(1f))
                StatTile("file", formatBytes(state.databaseBytes), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                StatTile(
                    "refreshed",
                    state.refreshedAtMillis?.let(::formatTime) ?: "—",
                    Modifier.weight(1f),
                    MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        item {
            SectionCard(
                title = "Query & retention",
                subtitle = "Rows stream from RoomLogDao.observe(...); refresh() flushes the buffered pipeline and re-measures the file.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { controller.refresh() },
                        modifier = Modifier.weight(1f),
                        enabled = !state.loading,
                    ) { Text("Refresh", maxLines = 1) }
                    OutlinedButton(
                        onClick = { controller.purgeOlderThan(30) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Sweep > 30d", maxLines = 1) }
                }
                OutlinedButton(
                    onClick = { controller.purgeEverything() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete all persisted rows (demo)") }
                Text(
                    "Retention runs automatically after successful inserts, at most once per sweep interval, " +
                        "inside the dispatch worker. The manual sweep is here to make the effect visible.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard(
                title = "Wiring that produced this table",
                subtitle = "The logger exists before the database so lifecycle events are not lost.",
            ) {
                CodeBlock(
                    title = "LoggingModule.kt",
                    code = """
val target = RoomLogTarget(retention = RoomLogRetention.keepDays(30))
val logger = ILogger.create {
    dispatch = DispatchMode.BUFFERED
    sink(target)                              // durable, catch-all
}
val db = Room.databaseBuilder<AppDatabase>(context, "app.db")
    .withAuditCallback(logger, label = "app.db")
    .build()
    .also { target.bind(it.logDao()) }        // drains queued lifecycle events

val dao = db.logged(logger).taskDao()         // generated @LogDao facade
""".trimIndent(),
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Persisted events (${state.rows.size})",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = { controller.refresh() }) { Text("Reload") }
            }
            Text(
                "Showing the 250 newest rows. Tap any row for its flat columns and the lossless payload.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.rows.isEmpty()) {
            item {
                SectionCard(title = "No persisted rows") {
                    EmptyState(
                        "The table is empty",
                        "Run an action on the task board, then refresh (or wait for the buffer to drain).",
                    )
                }
            }
        } else {
            items(state.rows, key = { it.eventId }) { row ->
                PersistedEventRow(row = row, onClick = { selected = row })
            }
        }
    }

    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = {
                Text(row.action, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LevelPill(runCatching { LogLevel.valueOf(row.level) }.getOrDefault(LogLevel.TRACE))
                        Text(
                            "  ${row.outcome}  ${formatTime(row.ts)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    PropertyRow("Event id", row.eventId, monospace = true)
                    PropertyRow("Action", row.action, monospace = true)
                    PropertyRow("Version", row.actionVersion.toString())
                    PropertyRow("Entity", row.entity)
                    PropertyRow("Entity id", row.entityId, monospace = true)
                    PropertyRow("Actor", row.actorId, monospace = true)
                    PropertyRow("Source", row.source, monospace = true)
                    PropertyRow("Category", row.category)
                    PropertyRow("Correlation", row.correlationId, monospace = true)
                    PropertyRow("Causation", row.causationId, monospace = true)
                    Text("Payload", style = MaterialTheme.typography.labelLarge)
                    CodeBlock(title = "payload.json", code = prettyRawJson(row.payload))
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun PersistedEventRow(row: RoomLogEntity, onClick: () -> Unit) {
    val level = runCatching { LogLevel.valueOf(row.level) }.getOrDefault(LogLevel.TRACE)
    val outcome = runCatching { Outcome.valueOf(row.outcome) }.getOrDefault(Outcome.SUCCESS)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelPill(level)
                if (outcome != Outcome.SUCCESS) {
                    Text(
                        "  ${outcome.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    formatTime(row.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                row.action + if (row.actionVersion > 1) "@v${row.actionVersion}" else "",
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            val attribution = buildString {
                row.actorId?.let { append("actor=").append(it) }
                row.entity?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                    row.entityId?.let { id -> append("(").append(id).append(")") }
                }
                row.source?.let {
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
            row.correlationId?.let {
                Text(
                    "corr=$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

