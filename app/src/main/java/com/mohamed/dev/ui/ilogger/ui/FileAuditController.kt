package com.mohamed.dev.ui.ilogger.ui

import com.mohamed.dev.ui.ilogger.logging.AuditFileSnapshot
import com.mohamed.dev.ui.ilogger.logging.AuditFileStore
import com.mohamed.dev.ui.ilogger.logging.DemoSession
import io.ilogger.core.ILogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Drives the category-routed JSONL sink and reads it back for inspection. */
@Singleton
class FileAuditController @Inject constructor(
    private val logger: ILogger,
    private val store: AuditFileStore,
    private val session: DemoSession,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshot = MutableStateFlow(store.snapshot())
    val snapshot: StateFlow<AuditFileSnapshot> = _snapshot.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var sequence = 0

    fun refresh() {
        scope.launch {
            _snapshot.value = store.snapshot()
        }
    }

    /**
     * Emits audit-category events. Only this category reaches the file sink; the
     * same events still reach the catch-all feed and Room bindings.
     */
    fun writeSamples(count: Int = 40) {
        scope.launch {
            if (_busy.value) return@launch
            _busy.value = true
            try {
                session.run {
                    repeat(count) { index ->
                        val n = ++sequence
                        logger.record(
                            action = "audit.sample",
                            entity = "AuditFile",
                            entityId = n.toString(),
                            metadata = mapOf(
                                "index" to index,
                                "kind" to "jsonl-demo",
                                "note" to "one compact JSON object per line",
                            ),
                            source = "FileAuditController.writeSamples",
                            category = "audit",
                        )
                    }
                }
                logger.flush()
                _snapshot.value = store.snapshot()
                _messages.tryEmit("Wrote $count audit-category event(s); rotation is size-based")
            } finally {
                _busy.value = false
            }
        }
    }

    fun rotateNow() {
        _messages.tryEmit("Writing 80 events to force at least one size-based roll")
        writeSamples(80)
    }
}
