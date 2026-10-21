package com.mohamed.dev.ui.ilogger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.ilogger.logging.DispatchIssue
import com.mohamed.dev.ui.ilogger.ui.DemoController
import com.mohamed.dev.ui.ilogger.ui.components.EmptyState
import com.mohamed.dev.ui.ilogger.ui.components.EventCard
import com.mohamed.dev.ui.ilogger.ui.components.EventDetailDialog
import com.mohamed.dev.ui.ilogger.ui.components.LevelPill
import com.mohamed.dev.ui.ilogger.ui.components.ScreenIntro
import com.mohamed.dev.ui.ilogger.ui.components.SectionCard
import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import io.ilogger.core.Outcome

@Composable
fun EventLogScreen(
    controller: DemoController,
    onPausedChange: (Boolean) -> Unit,
) {
    val feed = controller.feed
    val live by feed.events.collectAsState()
    val issues by feed.dispatchIssues.collectAsState()
    val received by feed.receivedCount.collectAsState()

    var paused by remember { mutableStateOf(feed.paused) }
    var frozen by remember { mutableStateOf<List<LogEvent>>(emptyList()) }
    var selected by remember { mutableStateOf<LogEvent?>(null) }
    var query by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var outcomeFilter by remember { mutableStateOf<Outcome?>(null) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(paused) {
        feed.paused = paused
        onPausedChange(paused)
        if (paused) frozen = feed.events.value
    }

    val shown = if (paused) frozen else live
    val categories = shown.map { it.category ?: "default" }.distinct().sorted()
    val filtered = remember(shown, query, levelFilter, outcomeFilter, categoryFilter) {
        val needle = query.trim()
        shown.filter { event ->
            (levelFilter == null || event.level == levelFilter) &&
                (outcomeFilter == null || event.outcome == outcomeFilter) &&
                (categoryFilter == null || (event.category ?: "default") == categoryFilter) &&
                (needle.isEmpty() || event.matches(needle))
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenIntro(
                "Live event inspector",
                "The catch-all in-memory sink behind every screen. Filter by level, outcome or category, " +
                    "then open an event for its full typed payload and lossless JSON.",
            )
        }

        item {
            SectionCard(
                title = "Filters — $received received, ${shown.size} buffered, ${filtered.size} shown",
                subtitle = "The buffer caps at ${com.mohamed.dev.ui.ilogger.logging.EventBuffer.MAX_EVENTS} events; memory stays bounded.",
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search action, actor, source, metadata…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = paused,
                        onCheckedChange = { paused = it },
                    )
                }
                Text(
                    if (paused) "Paused — displayed events are frozen; new ones are ignored by the live view."
                    else "Live — pause before inspecting a busy stream.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = levelFilter == null,
                            onClick = { levelFilter = null },
                            label = { Text("All levels", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    items(LogLevel.entries.toList()) { level ->
                        FilterChip(
                            selected = levelFilter == level,
                            onClick = { levelFilter = if (levelFilter == level) null else level },
                            label = { Text(level.name, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = outcomeFilter == null,
                            onClick = { outcomeFilter = null },
                            label = { Text("All outcomes", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    items(Outcome.entries.toList()) { outcome ->
                        FilterChip(
                            selected = outcomeFilter == outcome,
                            onClick = { outcomeFilter = if (outcomeFilter == outcome) null else outcome },
                            label = { Text(outcome.name, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = categoryFilter == null,
                            onClick = { categoryFilter = null },
                            label = { Text("All categories", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    items(categories) { category ->
                        FilterChip(
                            selected = categoryFilter == category,
                            onClick = { categoryFilter = if (categoryFilter == category) null else category },
                            label = { Text(category, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { controller.flushLogger() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Flush to sinks", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            controller.clearFeed()
                            frozen = emptyList()
                            selected = null
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Clear live view", maxLines = 1) }
                }
            }
        }

        if (issues.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Dispatch issues (${issues.size})",
                    subtitle = "onDispatchError reports overflow, closed-logger drops, retries exhausted and recursion.",
                ) {
                    issues.take(4).forEach { issue -> IssueRow(issue) }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                SectionCard(title = "No matching events") {
                    EmptyState(
                        "Nothing to show",
                        if (shown.isEmpty()) "Open the task board and run an action." else "Relax the filters.",
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { event ->
                EventCard(event, onClick = { selected = event })
            }
        }
    }

    selected?.let { event ->
        EventDetailDialog(event = event, onDismiss = { selected = null })
    }
}

@Composable
private fun IssueRow(issue: DispatchIssue) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LevelPill(LogLevel.ERROR)
        Text(
            issue.message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        issue.action?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LogEvent.matches(needle: String): Boolean {
    if (action.contains(needle, ignoreCase = true)) return true
    if (schemaId.contains(needle, ignoreCase = true)) return true
    if (source?.contains(needle, ignoreCase = true) == true) return true
    if (entity?.contains(needle, ignoreCase = true) == true) return true
    if (entityId?.contains(needle, ignoreCase = true) == true) return true
    if (actor?.id?.contains(needle, ignoreCase = true) == true) return true
    if (actor?.name?.contains(needle, ignoreCase = true) == true) return true
    if (actor?.type?.name?.contains(needle, ignoreCase = true) == true) return true
    if (correlationId?.contains(needle, ignoreCase = true) == true) return true
    if (id.contains(needle, ignoreCase = true)) return true
    return metadata.entries.any { (key, value) ->
        key.contains(needle, ignoreCase = true) || value?.toString()?.contains(needle, ignoreCase = true) == true
    }
}
