package com.mohamed.dev.ui.ilogger.logging

import android.app.Application
import io.ilogger.annotations.DispatchMode
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.ILogger
import io.ilogger.core.sink.ConsoleLogSink
import io.ilogger.core.sink.DefaultTextEventCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Application-owned logger. ILogger's core is framework-independent, so the
 * Android touchpoints are only: an [Application] subclass owning the lifetime
 * (flush on background) and a custom [LogSink] feeding UI state.
 */
class ILoggerApplication : Application() {

    /** Flush on background; forensic durability exactly when the OS may freeze us. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLogger.logger.flush()
    }
}

/**
 * Singleton wiring:
 * - ASYNC dispatch (UI threads never block on event handling)
 * - a sink owned by the app: collects the last 50 events into Compose state
 * - a console sink: logcat visibility
 */
object AppLogger {

    private val _eventLines = MutableStateFlow<List<String>>(emptyList())

    /** Latest-first view of collected events for the UI. */
    val eventLines: StateFlow<List<String>> = _eventLines

    private val collectingSink = object : LogSink {
        override suspend fun write(event: LogEvent) {
            val line = DefaultTextEventCodec.encode(event)
            _eventLines.update { previous -> (listOf(line) + previous).take(50) }
        }
    }

    val logger: ILogger = ILogger.create {
        dispatch = DispatchMode.ASYNC
        sink(collectingSink)
        sink(ConsoleLogSink())
    }
}
