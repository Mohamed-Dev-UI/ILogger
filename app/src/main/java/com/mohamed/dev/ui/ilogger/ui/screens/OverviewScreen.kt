package com.mohamed.dev.ui.ilogger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.ilogger.logging.AuditMetricsSnapshot
import com.mohamed.dev.ui.ilogger.ui.DemoDestination
import com.mohamed.dev.ui.ilogger.ui.components.EventCard
import com.mohamed.dev.ui.ilogger.ui.components.Pill
import com.mohamed.dev.ui.ilogger.ui.components.PropertyRow
import com.mohamed.dev.ui.ilogger.ui.components.SectionCard
import com.mohamed.dev.ui.ilogger.ui.components.StatTile
import io.ilogger.core.LogEvent

@Composable
fun OverviewScreen(
    metrics: AuditMetricsSnapshot,
    recentEvents: List<LogEvent>,
    droppedCount: Int,
    onNavigate: (DemoDestination) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HeroCard(onNavigate = onNavigate)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("captured", metrics.total.toString(), Modifier.weight(1f))
                StatTile("audit", metrics.audit.toString(), Modifier.weight(1f))
                StatTile("failures", metrics.failures.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                StatTile("dropped", droppedCount.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.outline)
            }
        }
        item {
            SectionCard(
                title = "Walk the whole library",
                subtitle = "Each stop is a runnable code path, not a mock. Copy the recipes into your app.",
            ) {
                FeatureRow(
                    glyph = "▣",
                    title = "Task board — annotated domain",
                    body = "Actor switching, correlation, changes, START/FAILURE, extractors, suppression.",
                    onClick = { onNavigate(DemoDestination.TASKS) },
                )
                FeatureRow(
                    glyph = "≋",
                    title = "Live events — the inspector",
                    body = "Filter every emitted event, inspect metadata/changes/failure, copy lossless JSON.",
                    onClick = { onNavigate(DemoDestination.EVENTS) },
                )
                FeatureRow(
                    glyph = "▤",
                    title = "Room audit — durable trail",
                    body = "Library-owned log table, lifecycle events, retention sweeps, restart-proof history.",
                    onClick = { onNavigate(DemoDestination.ROOM) },
                )
                FeatureRow(
                    glyph = "▥",
                    title = "JSONL file — category routing",
                    body = "Only audit-category events reach a rotated append-only file with the JSON codec.",
                    onClick = { onNavigate(DemoDestination.FILES) },
                )
                FeatureRow(
                    glyph = "⛉",
                    title = "Protection — compile-time privacy",
                    body = "HASH and MASK policies on @LogSensitive values; raw notes never enter an event.",
                    onClick = { onNavigate(DemoDestination.PROTECTION) },
                )
                FeatureRow(
                    glyph = "⚙",
                    title = "Engine lab — run the internals",
                    body = "Bounded buffers, BLOCK vs drop, retries, PROPAGATE, sampling and the off-switch, measured live.",
                    onClick = { onNavigate(DemoDestination.LAB) },
                )
                FeatureRow(
                    glyph = "✓",
                    title = "TestKit — deterministic checks",
                    body = "A real suite with FixedClock and sequential ids, run inside the app.",
                    onClick = { onNavigate(DemoDestination.TESTKIT) },
                )
            }
        }
        item {
            SectionCard(
                title = "This app's logger",
                subtitle = "One production-shaped instance; every screen shares it.",
            ) {
                PropertyRow("Dispatch", "BUFFERED · buffer 512 · batch 32 · overflow DROP_OLDEST")
                PropertyRow("Sinks", "live feed (catch-all) · Room (catch-all) · JSONL \"audit\" · Logcat \"diagnostics\"")
                PropertyRow("Audit errors", "RETRY with bounded backoff; DROP for diagnostics")
                PropertyRow("Retention", "30 days, swept inside the dispatch worker")
                PropertyRow("Triggers", "onEvents → dashboard metrics, failure-isolated")
                PropertyRow("Lifecycle", "Room database created/opened events queued before bind()")
            }
        }
        if (metrics.topActions.isNotEmpty()) {
            item {
                SectionCard(title = "Hottest actions", subtitle = "Computed by the onEvents trigger, off the producer path.") {
                    val max = metrics.topActions.maxOf { it.count }.coerceAtLeast(1)
                    metrics.topActions.forEach { action ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row {
                                Text(
                                    action.action,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    action.count.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(action.count.toFloat() / max)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        metrics.categoryCounts.entries.take(4).forEach { (category, count) ->
                            Pill("$category $count", MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
        }
        item {
            SectionCard(title = "Latest event", subtitle = "The same immutable LogEvent every sink receives.") {
                val latest = recentEvents.firstOrNull()
                if (latest == null) {
                    Text(
                        "No events yet. Open the task board and create one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    EventCard(latest, onClick = { onNavigate(DemoDestination.EVENTS) })
                }
            }
        }
        item {
            SectionCard(
                title = "Modules in this repo",
                subtitle = "The demo exercises the Android-facing surface of every runtime module.",
            ) {
                ModuleRow("ilogger-annotations", "@Log, @LogMeta, @LogChange, @LogDao, protection annotations")
                ModuleRow("ilogger-core", "engine, context, sinks, triggers, masking, file sink")
                ModuleRow("ilogger-ksp", "generated wrappers, entity/id inference, @LogDao facade")
                ModuleRow("ilogger-room", "RoomLogTarget, RoomLogEntity, lifecycle callback, retention")
                ModuleRow("ilogger-serialization", "JsonEventCodec JSONL payloads")
                ModuleRow("ilogger-testing", "TestKit, FixedClock, SequentialIdGenerator")
                ModuleRow("ilogger-ktor", "server recipe on the Recipes screen (Android cannot host the sample)")
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun HeroCard(onNavigate: (DemoDestination) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f),
                    ),
                ),
            )
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Android", MaterialTheme.colorScheme.primary)
                Pill("Hilt", MaterialTheme.colorScheme.secondary)
                Pill("Room", MaterialTheme.colorScheme.tertiary)
            }
            Text(
                "Audit by\nconstruction",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Mark an interface with @Log and the processor proves entity, id, actor and payload from the signature. " +
                    "This app is the full tour: annotated Room DAO, repository, actors, changes, failures, routing, " +
                    "retention and deterministic tests.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    onClick = { onNavigate(DemoDestination.TASKS) },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        "Open the task board  →",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.width(2.dp))
                Surface(
                    onClick = { onNavigate(DemoDestination.RECIPES) },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                ) {
                    Text(
                        "Recipes",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(
    glyph: String,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                glyph,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    body,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModuleRow(module: String, role: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            module,
            modifier = Modifier.width(148.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            role,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
