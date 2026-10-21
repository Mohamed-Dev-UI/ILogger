package com.mohamed.dev.ui.ilogger.logging

import io.ilogger.annotations.LogLevel
import io.ilogger.core.LogEvent
import io.ilogger.core.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class ActionCount(val action: String, val count: Int)

data class AuditMetricsSnapshot(
    val total: Int = 0,
    val audit: Int = 0,
    val diagnostic: Int = 0,
    val failures: Int = 0,
    val lastFailureAction: String? = null,
    val lastEventMillis: Long? = null,
    val actionCounts: Map<String, Int> = emptyMap(),
    val categoryCounts: Map<String, Int> = emptyMap(),
) {
    val topActions: List<ActionCount>
        get() = actionCounts.entries
            .sortedByDescending { it.value }
            .take(6)
            .map { ActionCount(it.key, it.value) }
}

/**
 * Metrics fed through the logger's `onEvents` trigger.
 *
 * A trigger is failure-isolated: a broken aggregation can never fail the
 * instrumented operation, and batches arrive whole under BUFFERED dispatch.
 */
@Singleton
class AuditMetrics @Inject constructor() {

    private val _snapshot = MutableStateFlow(AuditMetricsSnapshot())
    val snapshot: StateFlow<AuditMetricsSnapshot> = _snapshot.asStateFlow()

    fun record(events: List<LogEvent>) {
        if (events.isEmpty()) return
        _snapshot.update { current ->
            var audit = current.audit
            var diagnostic = current.diagnostic
            var failures = current.failures
            var lastFailure = current.lastFailureAction
            var lastAt = current.lastEventMillis
            val actions = current.actionCounts.toMutableMap()
            val categories = current.categoryCounts.toMutableMap()

            for (event in events) {
                if (event.level == LogLevel.AUDIT) audit++ else diagnostic++
                if (event.outcome == Outcome.FAILURE) {
                    failures++
                    lastFailure = event.schemaId
                }
                actions[event.schemaId] = (actions[event.schemaId] ?: 0) + 1
                val category = event.category ?: "default"
                categories[category] = (categories[category] ?: 0) + 1
                lastAt = maxOf(lastAt ?: event.timestampMillis, event.timestampMillis)
            }

            current.copy(
                total = current.total + events.size,
                audit = audit,
                diagnostic = diagnostic,
                failures = failures,
                lastFailureAction = lastFailure,
                lastEventMillis = lastAt,
                actionCounts = actions,
                categoryCounts = categories,
            )
        }
    }

    fun reset() {
        _snapshot.value = AuditMetricsSnapshot()
    }
}
