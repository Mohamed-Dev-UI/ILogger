package com.mohamed.dev.ui.ilogger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.ilogger.ui.FileAuditController
import com.mohamed.dev.ui.ilogger.ui.components.CodeBlock
import com.mohamed.dev.ui.ilogger.ui.components.EmptyState
import com.mohamed.dev.ui.ilogger.ui.components.PropertyRow
import com.mohamed.dev.ui.ilogger.ui.components.ScreenIntro
import com.mohamed.dev.ui.ilogger.ui.components.SectionCard
import com.mohamed.dev.ui.ilogger.ui.components.StatTile
import com.mohamed.dev.ui.ilogger.ui.components.formatBytes

@Composable
fun FileAuditScreen(controller: FileAuditController) {
    val snapshot by controller.snapshot.collectAsState()
    val busy by controller.busy.collectAsState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(controller) { controller.refresh() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenIntro(
                "JSONL file sink",
                "Only events with category = \"audit\" reach this file. The sink appends one compact JSON object " +
                    "per line and rotates by size into audit.jsonl.1, .2, .3 — ideal for forensic shipping.",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("total", formatBytes(snapshot.totalBytes), Modifier.weight(1f))
                StatTile("files", snapshot.files.size.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                StatTile("tail lines", snapshot.tail.size.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
            }
        }
        item {
            SectionCard(
                title = "Sink configuration",
                subtitle = "TextFileSink + JsonEventCodec, bound to the audit category.",
            ) {
                PropertyRow("Directory", snapshot.directory, monospace = true)
                PropertyRow("File", snapshot.path, monospace = true)
                PropertyRow("Rotation", "12 KB per file · keep 3 rolled files", monospace = true)
                PropertyRow("Durability", "flushEveryWrite = true", monospace = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(snapshot.path)) }) {
                        Text("Copy path")
                    }
                    TextButton(onClick = { controller.refresh() }) { Text("Refresh sizes") }
                }
                CodeBlock(
                    title = "LoggerModule.kt",
                    code = """
ILogger.create {
    dispatch = DispatchMode.BUFFERED
    sink(feed.sink)                       // catch-all live view
    sink(
        TextFileSink(
            file = File(context.filesDir, "audit/audit.jsonl").toPath(),
            codec = JsonEventCodec,        // one JSON object per line
            rotation = TextFileSink.Rotation(maxBytesPerFile = 12 * 1024, maxFiles = 3),
        ),
        category = "audit",                // audit events only
    )
}
""".trimIndent(),
                )
            }
        }
        item {
            SectionCard(
                title = "Write audit events",
                subtitle = "The generated file receives only these category=\"audit\" events; Room and the live feed still see everything.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { controller.writeSamples(40) },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text("Write 40", maxLines = 1) }
                    OutlinedButton(
                        onClick = { controller.rotateNow() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text("Write 80 (roll)", maxLines = 1) }
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Writing, flushing the pipeline and re-reading the directory…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "The archive action on the task board also writes here (task.archived@v2, category \"audit\").",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard(
                title = "Files on disk",
                subtitle = "Rotation happens before a write that would exceed 12 KB.",
            ) {
                if (snapshot.files.isEmpty()) {
                    EmptyState("No files yet", "Write audit events to create the append-only file.")
                } else {
                    snapshot.files.forEach { file ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    file.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    formatBytes(file.bytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionCard(
                title = "Tail of audit.jsonl",
                subtitle = "Read back from the real file the sink wrote.",
            ) {
                if (snapshot.tail.isEmpty()) {
                    EmptyState(
                        "Nothing to read yet",
                        "Write audit events above; the tail refreshes after flush.",
                    )
                } else {
                    CodeBlock(
                        title = "audit.jsonl · last ${snapshot.tail.size} lines",
                        code = snapshot.tail.joinToString("\n"),
                    )
                }
            }
        }
        item {
            SectionCard(
                title = "Why a second durable destination?",
                subtitle = "Room answers queries; JSONL survives as an append-only forensic artifact.",
            ) {
                PropertyRow("Room", "queryable, indexed, retention sweep, in-app viewer")
                PropertyRow("JSONL", "cheap append + rotation, ships off-device with one file")
                PropertyRow("Category", "diagnostic noise never reaches the strict durable file")
                PropertyRow("Codec", "JsonEventCodec is lossless; DefaultTextEventCodec is for humans")
            }
        }
    }
}
