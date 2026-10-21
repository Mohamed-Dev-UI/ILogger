package com.mohamed.dev.ui.ilogger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohamed.dev.ui.ilogger.logging.DemoCast
import com.mohamed.dev.ui.ilogger.logging.DemoSession
import com.mohamed.dev.ui.ilogger.logging.Task
import com.mohamed.dev.ui.ilogger.logging.TaskPriority
import com.mohamed.dev.ui.ilogger.logging.TaskStatus
import com.mohamed.dev.ui.ilogger.ui.DemoController
import com.mohamed.dev.ui.ilogger.ui.components.CodeBlock
import com.mohamed.dev.ui.ilogger.ui.components.EmptyState
import com.mohamed.dev.ui.ilogger.ui.components.PickerField
import com.mohamed.dev.ui.ilogger.ui.components.Pill
import com.mohamed.dev.ui.ilogger.ui.components.PropertyRow
import com.mohamed.dev.ui.ilogger.ui.components.ScreenIntro
import com.mohamed.dev.ui.ilogger.ui.components.SectionCard
import com.mohamed.dev.ui.ilogger.ui.components.SwitchRow

@Composable
fun TaskBoardScreen(controller: DemoController) {
    val tasks by controller.tasks.collectAsState()
    val busy by controller.busy.collectAsState()
    val session = controller.session
    val cast by session.cast.collectAsState()
    val tenant by session.tenant.collectAsState()
    val correlationEnabled by session.correlationEnabled.collectAsState()
    val suppressStorage by session.suppressStorage.collectAsState()
    val lastCorrelation by session.lastCorrelationId.collectAsState()
    val searchResults by controller.searchResults.collectAsState()

    var newTitle by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf(TaskPriority.NORMAL) }
    var query by rememberSaveable { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Task?>(null) }
    var renameText by remember { mutableStateOf("") }
    var escalateTarget by remember { mutableStateOf<Task?>(null) }
    var operatorId by remember { mutableStateOf("support-01") }
    var deleteTarget by remember { mutableStateOf<Task?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenIntro(
                "Annotated task board",
                "Create, rename, complete, delete, archive, batch-insert and fail tasks. Every action runs through " +
                    "the generated TaskRepository and Room TaskDao wrappers; the session controls below decide what " +
                    "ambient context the events inherit.",
            )
        }

        item {
            SectionCard(
                title = "Session",
                subtitle = "Actor, tenant attribute, correlation and the suppressSources toggle.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickerField(
                        label = "Actor",
                        value = cast,
                        options = DemoCast.entries,
                        optionLabel = { it.label },
                        onSelect = { session.selectCast(it) },
                        modifier = Modifier.weight(1.4f),
                    )
                    PickerField(
                        label = "Tenant",
                        value = tenant,
                        options = DemoSession.TENANTS,
                        optionLabel = { it },
                        onSelect = { session.selectTenant(it) },
                        modifier = Modifier.weight(0.6f),
                    )
                }
                Text(
                    cast.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                SwitchRow(
                    title = "Correlation per action",
                    checked = correlationEnabled,
                    onCheckedChange = { session.setCorrelationEnabled(it) },
                    subtitle = "All events of one operation share a withCorrelation id",
                )
                if (lastCorrelation != null) {
                    Pill("last corr=${lastCorrelation}", MaterialTheme.colorScheme.secondary)
                }
                SwitchRow(
                    title = "Suppress storage duplicates",
                    checked = suppressStorage,
                    onCheckedChange = { session.setSuppressStorage(it) },
                    subtitle = "withLogContext(suppressSources = TaskDao.*) — domain event stays",
                )
                PropertyRow("Actor factory", when (cast) {
                    DemoCast.USER -> "Actor.user(\"u-42\", name = \"Grace\")"
                    DemoCast.SYSTEM -> "Actor.system(...)"
                    DemoCast.WORKER -> "Actor.worker(\"job-7\", ...)"
                    DemoCast.AUTOMATION -> "Actor.automation(\"rule-3\", ...)"
                    DemoCast.SERVICE -> "Actor.service(\"svc-mobile\", ...)"
                    DemoCast.ANONYMOUS -> "Actor.anonymous()"
                }, monospace = true)
            }
        }

        item {
            SectionCard(
                title = "New task",
                subtitle = "create() uses @Log(entity = Task::class, entityId = \"result\", metadata = [title, priority, context.tenantId]).",
            ) {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(TaskPriority.entries) { entry ->
                        FilterChip(
                            selected = priority == entry,
                            onClick = { priority = entry },
                            label = { Text(entry.name, style = MaterialTheme.typography.labelSmall) },
                            enabled = !busy,
                        )
                    }
                }
                Button(
                    onClick = {
                        controller.createTask(newTitle, priority)
                        newTitle = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text("Create task (audited)")
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }

        item {
            SectionCard(
                title = "Boundary & failure demos",
                subtitle = "Batch extractor, onFailure, causation, Flow collection, diagnostics and the recordNow bridge.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { controller.insertBatch() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text("Import 3", maxLines = 1) }
                    OutlinedButton(
                        onClick = { controller.emitDiagnostics() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text("Diagnostics", maxLines = 1) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { controller.explode() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text("Trigger FAILURE", maxLines = 1) }
                    OutlinedButton(
                        onClick = { controller.retryWithCausation() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text("Retry + cause", maxLines = 1) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { controller.collectStream() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text("Collect Flow", maxLines = 1) }
                    OutlinedButton(
                        onClick = { controller.rawStorageInsert() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text("Raw DAO insert", maxLines = 1) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { controller.flushLogger() },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text("Flush pipeline", maxLines = 1) }
                    OutlinedButton(
                        onClick = { controller.clearFeed() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Clear feed", maxLines = 1) }
                }
            }
        }

        item {
            SectionCard(
                title = "Search — manual record",
                subtitle = "No @Log here: a boundary event recorded with logger.record(...) at the call site.",
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Title contains") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    )
                    Button(
                        onClick = { controller.search(query) },
                        enabled = !busy && query.isNotBlank(),
                    ) { Text("Go") }
                }
                val results = searchResults
                if (results != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${results.size} hit(s)",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        TextButton(onClick = { controller.clearSearch() }) { Text("Clear") }
                    }
                    if (results.isEmpty()) {
                        Text(
                            "No matching tasks; the INFO diagnostics event is still recorded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    results.forEach { task ->
                        Text(
                            "#${task.id} · ${task.title} · ${task.status.name}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tasks (${tasks.size})",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = { controller.refreshTasks() }) { Text("Refresh") }
            }
        }

        if (tasks.isEmpty()) {
            item {
                SectionCard(title = "No tasks yet") {
                    EmptyState(
                        "The board is empty",
                        "Create a task above; every state change is audited.",
                    )
                }
            }
        } else {
            items(tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    busy = busy,
                    onComplete = { controller.completeTask(task) },
                    onRename = {
                        renameTarget = task
                        renameText = task.title
                    },
                    onDelete = { deleteTarget = task },
                    onArchive = { controller.archiveTask(task) },
                    onEscalate = {
                        escalateTarget = task
                        operatorId = "support-01"
                    },
                    onSummarize = { controller.summarizeTask(task) },
                    onInlineAudit = { controller.auditInline(task) },
                    onCache = { controller.syncCacheTask(task) },
                )
            }
        }

        item {
            SectionCard(
                title = "Copy this contract",
                subtitle = "The annotations below generate the wrapper this screen calls.",
            ) {
                CodeBlock(
                    title = "TaskRepository.kt",
                    code = """
interface TaskRepository {
    @Log(
        action = TaskActions.CREATED,
        entity = Task::class,
        entityId = "result",
        metadata = [LogMeta("title"), LogMeta("priority"), LogMeta("context.tenantId")],
    )
    suspend fun create(title: String, priority: TaskPriority): Long

    @Log(
        action = TaskActions.RENAMED,
        entity = Task::class,
        entityId = "id",
        changes = [LogChange(field = "title", from = "currentTitle", to = "newTitle")],
        onFailure = true,
    )
    suspend fun rename(id: Long, currentTitle: String, newTitle: String): Boolean
}

val repository: TaskRepository = RoomTaskRepository(dao, session, logger).logged(logger)
""".trimIndent(),
                )
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename task") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.title, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("New title") },
                        singleLine = true,
                    )
                    Text(
                        "Recorded as LogChange(title, before → after) on the SUCCESS event only.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.renameTask(target, renameText)
                        renameTarget = null
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }

    escalateTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { escalateTarget = null },
            title = { Text("Escalate with actor override") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "@Log(actor = \"operatorId\") wraps the value in Actor.user(...) and overrides the ambient actor.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = operatorId,
                        onValueChange = { operatorId = it },
                        label = { Text("Operator id") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.escalateTask(target, operatorId)
                        escalateTarget = null
                    },
                    enabled = operatorId.isNotBlank(),
                ) { Text("Assign") }
            },
            dismissButton = { TextButton(onClick = { escalateTarget = null }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete task #${target.id}?") },
            text = { Text("The audit records START then SUCCESS (before = true); a thrown error would add FAILURE.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.deleteTask(target)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TaskCard(
    task: Task,
    busy: Boolean,
    onComplete: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onEscalate: () -> Unit,
    onSummarize: () -> Unit,
    onInlineAudit: () -> Unit,
    onCache: () -> Unit,
) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Pill("#${task.id}", MaterialTheme.colorScheme.secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(task.priority.name, MaterialTheme.colorScheme.tertiary)
                Pill(task.status.name, MaterialTheme.colorScheme.primary)
                task.assignee?.let { Pill("→ $it", MaterialTheme.colorScheme.secondary) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                    enabled = !busy && task.status == TaskStatus.OPEN,
                ) { Text("Complete", maxLines = 1) }
                OutlinedButton(
                    onClick = onRename,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) { Text("Rename", maxLines = 1) }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Less" else "More")
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onArchive,
                            modifier = Modifier.weight(1f),
                            enabled = !busy && task.status != TaskStatus.ARCHIVED,
                        ) { Text("Archive", maxLines = 1) }
                        OutlinedButton(
                            onClick = onEscalate,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        ) { Text("Escalate", maxLines = 1) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onSummarize,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        ) { Text("Summarize", maxLines = 1) }
                        OutlinedButton(
                            onClick = onInlineAudit,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        ) { Text("Inline audit", maxLines = 1) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onCache,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        ) { Text("Cache sync", maxLines = 1) }
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        ) {
                            Text("Delete", color = MaterialTheme.colorScheme.error, maxLines = 1)
                        }
                    }
                    Text(
                        "Archive = schema v2 + HASH/MASK + audit category · Summarize = extractor · " +
                            "Inline = sync delivery · Cache = recordNow + thread bridge",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
