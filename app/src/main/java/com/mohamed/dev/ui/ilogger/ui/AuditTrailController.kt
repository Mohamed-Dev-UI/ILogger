package com.mohamed.dev.ui.ilogger.ui

import android.content.Context
import com.mohamed.dev.ui.ilogger.logging.AppDatabase
import com.mohamed.dev.ui.ilogger.logging.LoggingModule
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ilogger.core.ILogger
import io.ilogger.room.RoomLogEntity
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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class AuditTrailState(
    val rows: List<RoomLogEntity> = emptyList(),
    val databaseBytes: Long = 0,
    val refreshedAtMillis: Long? = null,
    val loading: Boolean = false,
)

/** Reads the durable side of the demo: the library-owned `ilogger_events` table. */
@Singleton
class AuditTrailController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val logger: ILogger,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AuditTrailState())
    val state: StateFlow<AuditTrailState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch {
            _state.value = _state.value.copy(loading = true)
            logger.flush()
            val rows = runCatching { database.logDao().recent(limit = 250) }
                .onFailure { _messages.tryEmit("Room read failed: ${it.message}") }
                .getOrDefault(emptyList())
            val bytes = context.getDatabasePath(LoggingModule.DATABASE_NAME)
                .let { if (it.exists()) it.length() else 0L }
            _state.value = AuditTrailState(
                rows = rows,
                databaseBytes = bytes,
                refreshedAtMillis = System.currentTimeMillis(),
                loading = false,
            )
        }
    }

    fun purgeEverything() {
        scope.launch {
            val removed = runCatching { database.logDao().purgeBefore(Long.MAX_VALUE) }
                .onFailure { _messages.tryEmit("Purge failed: ${it.message}") }
                .getOrDefault(0)
            _messages.tryEmit("Deleted $removed persisted event(s); new events keep flowing")
            refresh()
        }
    }

    fun purgeOlderThan(days: Long) {
        scope.launch {
            val cutoff = System.currentTimeMillis() - days * 86_400_000L
            val removed = runCatching { database.logDao().purgeBefore(cutoff) }
                .onFailure { _messages.tryEmit("Purge failed: ${it.message}") }
                .getOrDefault(0)
            _messages.tryEmit("Retention sweep removed $removed row(s) older than $days day(s)")
            refresh()
        }
    }
}
