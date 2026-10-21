package com.mohamed.dev.ui.ilogger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.ilogger.ui.EngineLabController
import com.mohamed.dev.ui.ilogger.ui.LabScenario
import com.mohamed.dev.ui.ilogger.ui.components.CodeBlock
import com.mohamed.dev.ui.ilogger.ui.components.ScreenIntro
import com.mohamed.dev.ui.ilogger.ui.components.SectionCard

@Composable
fun LabScreen(controller: EngineLabController) {
    val results by controller.results.collectAsState()
    val running by controller.running.collectAsState()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenIntro(
                "Engine lab",
                "Each experiment builds an independent ILogger, runs against a measuring sink, then closes " +
                    "deterministically. The application's own logger is never mutated.",
            )
        }
        LabScenario.entries.forEach { scenario ->
            item {
                SectionCard(title = scenario.title, subtitle = scenario.blurb) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { controller.run(scenario) },
                            enabled = running == null,
                        ) { Text(if (running == scenario) "Running…" else "Run experiment") }
                        val result = results[scenario]
                        if (result != null) {
                            Text(
                                result.headline,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (running == scenario) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val result = results[scenario]
                    if (result != null) {
                        result.details.forEach { detail ->
                            Text(
                                "• $detail",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionCard(
                title = "The profile this app ships",
                subtitle = "Production-shaped: batched, durable, bounded and honest about failures.",
            ) {
                CodeBlock(
                    title = "ILogger.create",
                    code = """
val logger = ILogger.create {
    dispatch = DispatchMode.BUFFERED       // real batches to Room + file
    bufferSize = 512                       // small for mobile, finite by design
    batchSize = 32
    flushIntervalMillis = 150              // max age of a partial batch
    overflow = OverflowPolicy.DROP_OLDEST  // recency wins under flood
    closeTimeoutMillis = 3_000
    onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)
    onDispatchError = { event, sink, error -> telemetry(event, sink, error) }
}
// Application.onTrimMemory: logger.flush()
// whoever creates the logger closes it
""".trimIndent(),
                )
            }
        }
        item {
            SectionCard(
                title = "Rules of thumb",
                subtitle = "Pick once per app; per-event overrides stay the exception.",
            ) {
                Rule("Caller latency", "INLINE > ASYNC = BUFFERED (BUFFERED is the production default)")
                Rule("Durability before return", "INLINE + ErrorMode.PROPAGATE, only for money-movement style paths")
                Rule("Zero loss", "OverflowPolicy.BLOCK (producer slows) or FAIL (explicit exception)")
                Rule("Observability", "DROP_OLDEST + bounded buffer; a flood ages out instead of growing memory")
                Rule("Sink health", "AUDIT/ERROR → RETRY; diagnostics → DROP; PROPAGATE only with INLINE")
                Rule("Noise", "sampling applies to diagnostics; audit sampling requires an explicit application rate")
            }
        }
    }
}

@Composable
private fun Rule(title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            body,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
