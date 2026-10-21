package com.mohamed.dev.ui.ilogger.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.ilogger.ui.BenchCheck
import com.mohamed.dev.ui.ilogger.ui.TestKitController
import com.mohamed.dev.ui.ilogger.ui.components.CodeBlock
import com.mohamed.dev.ui.ilogger.ui.components.EmptyState
import com.mohamed.dev.ui.ilogger.ui.components.PropertyRow
import com.mohamed.dev.ui.ilogger.ui.components.ScreenIntro
import com.mohamed.dev.ui.ilogger.ui.components.SectionCard
import com.mohamed.dev.ui.ilogger.ui.components.StatTile
import com.mohamed.dev.ui.ilogger.ui.components.formatTime

@Composable
fun TestKitScreen(controller: TestKitController) {
    val checks by controller.checks.collectAsState()
    val running by controller.running.collectAsState()
    val lastRun by controller.lastRunMillis.collectAsState()
    val passed = checks.count { it.passed }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenIntro(
                "TestKit self-check",
                "The same deterministic kit the repository's ksp-tests use, executed on this device. " +
                    "INLINE dispatch, a fixed clock and sequential ids mean no sleeps and no flakes.",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("checks", checks.size.toString(), Modifier.weight(1f))
                StatTile("passed", passed.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.primary)
                StatTile(
                    "failed",
                    (checks.size - passed).toString(),
                    Modifier.weight(1f),
                    MaterialTheme.colorScheme.error,
                )
            }
        }
        item {
            SectionCard(
                title = if (checks.isEmpty()) "No run yet" else "Suite result",
                subtitle = lastRun?.let { "Last run at ${formatTime(it)}" } ?: "Assertions use structured LogEvent fields, never formatted strings.",
            ) {
                Button(
                    onClick = { controller.runChecks() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running,
                ) { Text(if (running) "Running…" else "Run the TestKit suite") }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (checks.isEmpty() && !running) {
                    EmptyState("Run the suite", "Ten checks cover identity, context, changes, failures and time.")
                }
            }
        }
        if (checks.isNotEmpty()) {
            item {
                SectionCard(
                    title = if (checks.all { it.passed }) "All checks passed" else "Attention needed",
                    subtitle = "Expected and actual are shown verbatim so a failure is actionable.",
                ) {
                    checks.forEach { check -> CheckRow(check) }
                }
            }
        }
        item {
            SectionCard(
                title = "The kit in nine lines",
                subtitle = "TestKit.create(configure) applies your overrides last.",
            ) {
                CodeBlock(
                    title = "NoteBookTest.kt",
                    code = """
val kit = TestKit.create()                  // INLINE + FixedClock + e1, e2, ...
val book = InMemoryNoteBook().logged(kit.logger)

book.create(Note(title = "write the audit"))

val event = kit.sink.last()
assertEquals("note.created", event.action)   // structured, not a string match
assertEquals("e1", event.id)
assertEquals(1_700_000_000_000L, event.timestampMillis)
assertEquals("Note", event.entity)
assertEquals("1", event.entityId)            // insert result is the id
kit.logger.close()
""".trimIndent(),
                )
            }
        }
        item {
            SectionCard(
                title = "Determinism pieces",
                subtitle = "Everything moving is injectable; the kit wires sane defaults.",
            ) {
                PropertyRow("TestKit", "pre-wired logger + TestLogSink + FixedClock + SequentialIdGenerator")
                PropertyRow("TestLogSink", "events(), last(), count(), clear(), awaitCount(n) for async modes")
                PropertyRow("FixedClock", "epoch 1_700_000_000_000 until you call set(millis)")
                PropertyRow("Sequential ids", "e1, e2, … — stable diff output, comparable in assertions")
                PropertyRow("Overrides", "configure { dispatch = BUFFERED } then sink.awaitCount(...)")
            }
        }
    }
}

@Composable
private fun CheckRow(check: BenchCheck) {
    val color = if (check.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (check.passed) "✓" else "✗",
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "  ${check.name}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (!check.passed) {
                Text(
                    "expected: ${check.expected}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "actual:   ${check.actual}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
