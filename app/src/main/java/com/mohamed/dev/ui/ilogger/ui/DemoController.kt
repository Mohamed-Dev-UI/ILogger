package com.mohamed.dev.ui.ilogger.ui

import com.mohamed.dev.ui.ilogger.logging.AuditMetrics
import com.mohamed.dev.ui.ilogger.logging.DemoSession
import com.mohamed.dev.ui.ilogger.logging.EventBuffer
import com.mohamed.dev.ui.ilogger.logging.SessionCache
import com.mohamed.dev.ui.ilogger.logging.Task
import com.mohamed.dev.ui.ilogger.logging.TaskDao
import com.mohamed.dev.ui.ilogger.logging.TaskPriority
import com.mohamed.dev.ui.ilogger.logging.TaskRepository
import com.mohamed.dev.ui.ilogger.logging.TaskStream
import io.ilogger.core.ILogger
import io.ilogger.core.LogContexts
import io.ilogger.core.Outcome
import io.ilogger.core.withCausation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped actions for the task board. UI state lives here so a
 * rotation or navigation never cancels an in-flight audited operation.
 */
@Singleton
class DemoController @Inject constructor(
    private val repository: TaskRepository,
    private val dao: TaskDao,
    private val logger: ILogger,
    val session: DemoSession,
    val feed: EventBuffer,
    val metrics: AuditMetrics,
    private val cache: SessionCache,
    private val stream: TaskStream,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val samples = AtomicInteger(0)
    private val running = AtomicBoolean(false)

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Task>?>(null)
    val searchResults: StateFlow<List<Task>?> = _searchResults.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        refreshTasks()
    }

    fun refreshTasks() {
        scope.launch {
            runCatching { dao.all() }
                .onSuccess { rows -> _tasks.value = rows }
                .onFailure { notify("Could not read tasks: ${it.friendly()}") }
        }
    }

    // ---- annotated repository operations -------------------------------------

    fun createTask(title: String, priority: TaskPriority) {
        if (title.isBlank()) return notify("Enter a task title first")
        action {
            val id = session.run { repository.create(title, priority) }
            notify("task.created → id=$id (insert result became the entity id)")
        }
    }

    fun renameTask(task: Task, newTitle: String) {
        if (newTitle.isBlank()) return notify("New title must not be blank")
        if (newTitle.trim() == task.title) return notify("Title is already \"${task.title}\"")
        action {
            session.run { repository.rename(task.id, task.title, newTitle) }
            _searchResults.value = null
            notify("task.renamed → before/after change recorded on SUCCESS")
        }
    }

    fun completeTask(task: Task) {
        action {
            session.run { repository.complete(task.id) }
            notify("task.completed → actor and tenant selected from ambient context")
        }
    }

    fun deleteTask(task: Task) {
        action {
            session.run { repository.delete(task.id) }
            _searchResults.value = null
            notify("task.deleted → START + SUCCESS (before = true)")
        }
    }

    fun archiveTask(task: Task) {
        action {
            session.run { repository.archive(task) }
            notify("task.archived@v2 → JSONL audit category, hashed + masked note")
        }
    }

    fun escalateTask(task: Task, operatorId: String) {
        if (operatorId.isBlank()) return notify("Operator id must not be blank")
        action {
            session.run { repository.escalate(task.id, operatorId) }
            notify("task.escalated → explicit actor override (operatorId)")
        }
    }

    fun summarizeTask(task: Task) {
        action {
            val length = session.run { repository.summarize(task) }
            notify("task.summarized → extractor projection of ${length} chars")
        }
    }

    /**
     * Protection demo: archive the first task, creating a seed row with a known
     * sensitive note when the board is empty. Both policies are selected on the
     * same value, so the resulting event shows the hash and the mask.
     */
    fun emitProtectedSample() {
        action {
            val target = _tasks.value.firstOrNull() ?: run {
                val id = session.storage {
                    dao.insert(
                        Task(
                            title = "Protected sample",
                            priority = TaskPriority.HIGH,
                            privateNote = PROTECTION_NOTE,
                        ),
                    )
                }
                Task(
                    id = id,
                    title = "Protected sample",
                    priority = TaskPriority.HIGH,
                    privateNote = PROTECTION_NOTE,
                )
            }
            session.run { repository.archive(target) }
            notify("task.archived@v2 → HASH + MASK emitted, raw note never selected")
        }
    }

    fun auditInline(task: Task) {
        action {
            session.run { repository.auditInline(task.id) }
            notify("INLINE audit written to every sink before the call returned")
        }
    }

    fun insertBatch() {
        action {
            val batch = (1..3).map {
                val n = samples.incrementAndGet()
                Task(
                    title = "Imported task $n",
                    priority = TaskPriority.entries[n % TaskPriority.entries.size],
                    privateNote = "imported note $n",
                )
            }
            val ids = session.run { repository.insertBatch(batch) }
            notify("task.batch.created → extractor summarized ${ids.size} ids")
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return notify("Enter a search term")
        action {
            val rows = session.run { repository.search(query) }
            _searchResults.value = rows
            notify("task.searched → manual record, INFO / diagnostics, ${rows.size} hit(s)")
        }
    }

    fun clearSearch() {
        _searchResults.value = null
    }

    // ---- failure, causation, manual and non-suspend paths --------------------

    fun explode() {
        action {
            val id = _tasks.value.firstOrNull()?.id ?: 1L
            val thrown = runCatching {
                session.run { repository.explode(id) }
            }.exceptionOrNull()
            if (thrown == null) {
                notify("Expected the demo failure to be thrown")
            } else {
                notify("FAILURE recorded, same ${thrown::class.java.simpleName} rethrown to the UI")
            }
        }
    }

    fun retryWithCausation() {
        val failed = feed.events.value.firstOrNull { it.outcome == Outcome.FAILURE }
        if (failed == null) return notify("Trigger the failure demo first, then retry")
        val task = _tasks.value.firstOrNull() ?: return notify("Create a task first")
        action {
            session.run {
                withCausation(failed.id) {
                    repository.complete(task.id)
                }
            }
            notify("causationId=${failed.id.take(8)} links this retry to the failure")
        }
    }

    fun rawStorageInsert() {
        action {
            val n = samples.incrementAndGet()
            val id = session.run {
                // Deliberately bypasses DemoSession.storage: the low-level
                // TaskDao event is kept, so both layers are visible.
                dao.insert(Task(title = "Raw DAO insert $n", privateNote = "raw note $n"))
            }
            notify("storage.task.inserted surfaced (suppression bypassed), id=$id")
        }
    }

    fun syncCacheTask(task: Task) {
        action {
            withContext(Dispatchers.Default) {
                // recordNow reads ONLY the thread-local bridge, never the coroutine context.
                LogContexts.onCurrentThread(session.threadContext()) {
                    cache.put(task)
                }
            }
            notify("cache.task.written → recordNow + onCurrentThread, delivered ASYNC")
        }
    }

    fun collectStream() {
        action {
            val count = session.run { stream.watch().count() }
            notify("Collected $count item(s); each change recorded manually at the collection site")
        }
    }

    fun emitDiagnostics() {
        action {
            withContext(Dispatchers.Default) {
                LogContexts.onCurrentThread(session.threadContext()) {
                    logger.trace("diagnostic trace", "run" to samples.incrementAndGet())
                    logger.debug("diagnostic debug", "engine" to "demo")
                    logger.info("diagnostic info", "screen" to "task-board")
                    logger.warn("diagnostic warn", "mode" to "demo")
                    logger.error("diagnostic error", "recoverable" to true)
                }
            }
            notify("5 diagnostics emitted → Logcat (diagnostics) + Room + live feed")
        }
    }

    fun flushLogger() {
        action {
            withContext(Dispatchers.IO) { logger.flush() }
            notify("logger.flush() drained the buffered pipeline")
        }
    }

    fun clearFeed() {
        feed.clear()
        metrics.reset()
        notify("Live feed cleared; durable Room rows are untouched")
    }

    fun setFeedPaused(paused: Boolean) {
        feed.paused = paused
    }

    // ---- plumbing ------------------------------------------------------------

    private fun action(block: suspend () -> Unit) {
        scope.launch {
            if (!running.compareAndSet(false, true)) return@launch
            _busy.value = true
            try {
                block()
            } catch (error: Throwable) {
                notify("⚠ ${error.friendly()}")
            } finally {
                _busy.value = false
                running.set(false)
                _tasks.value = runCatching { dao.all() }.getOrDefault(_tasks.value)
            }
        }
    }

    private fun notify(message: String) {
        _messages.tryEmit(message)
    }

    private fun Throwable.friendly(): String = message ?: this::class.java.simpleName

    companion object {
        const val PROTECTION_NOTE = "customer note: SSN 123-45-6789 — never leave the device"
    }
}
