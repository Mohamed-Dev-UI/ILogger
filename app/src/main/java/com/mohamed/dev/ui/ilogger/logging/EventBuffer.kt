package com.mohamed.dev.ui.ilogger.logging

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.sink.DefaultTextEventCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** One engine-level failure, surfaced instead of being silently swallowed. */
data class DispatchIssue(
    val action: String?,
    val message: String,
    val atMillis: Long = System.currentTimeMillis(),
)

/**
 * Bounded, latest-first live view of every event the logger emits, plus the
 * engine's reported dispatch failures. The sink owns the display concern:
 * nothing in the library knows about Compose.
 *
 * `lines` is kept for the instrumentation tests; `events` powers the UI and
 * keeps structured data around for filtering and JSON inspection.
 */
@Singleton
class EventBuffer @Inject constructor() {

    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _dispatchIssues = MutableStateFlow<List<DispatchIssue>>(emptyList())
    val dispatchIssues: StateFlow<List<DispatchIssue>> = _dispatchIssues.asStateFlow()

    private val _receivedCount = MutableStateFlow(0)
    val receivedCount: StateFlow<Int> = _receivedCount.asStateFlow()

    private val _droppedCount = MutableStateFlow(0)
    val droppedCount: StateFlow<Int> = _droppedCount.asStateFlow()

    @Volatile
    var paused: Boolean = false

    val sink: LogSink = object : LogSink {
        override suspend fun write(event: LogEvent) = accept(listOf(event))

        override suspend fun writeAll(events: List<LogEvent>) = accept(events)
    }

    private fun accept(batch: List<LogEvent>) {
        if (batch.isEmpty()) return
        _receivedCount.update { it + batch.size }
        if (paused) return
        _events.update { previous -> (batch.asReversed() + previous).take(MAX_EVENTS) }
        _lines.update { previous ->
            (batch.asReversed().map { DefaultTextEventCodec.encode(it) } + previous).take(MAX_LINES)
        }
    }

    /** Called by the logger's `onDispatchError`: engine failures are observable. */
    fun reportDispatchError(event: LogEvent?, sink: LogSink?, error: Throwable) {
        _droppedCount.update { it + 1 }
        _dispatchIssues.update { previous ->
            (
                listOf(
                    DispatchIssue(
                        action = event?.schemaId,
                        message = error.message ?: error::class.java.simpleName,
                    ),
                ) + previous
            ).take(MAX_ISSUES)
        }
    }

    fun reportRetentionError(error: Throwable) {
        _dispatchIssues.update { previous ->
            (
                listOf(
                    DispatchIssue(
                        action = "room.retention",
                        message = error.message ?: error::class.java.simpleName,
                    ),
                ) + previous
            ).take(MAX_ISSUES)
        }
    }

    fun clear() {
        _events.value = emptyList()
        _lines.value = emptyList()
        _dispatchIssues.value = emptyList()
        _receivedCount.value = 0
        _droppedCount.value = 0
    }

    companion object {
        const val MAX_EVENTS = 400
        const val MAX_LINES = 120
        private const val MAX_ISSUES = 20
    }
}
