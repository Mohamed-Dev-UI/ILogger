package com.mohamed.dev.ui.ilogger.logging

import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.sink.DefaultTextEventCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Latest-first, bounded (50 lines) view of emitted events for the demo UI.
 * The sink owns the display concern; the logger stays UI-agnostic.
 */
@Singleton
class EventBuffer @Inject constructor() {

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    val lines: StateFlow<List<String>> = _lines

    val sink: LogSink = object : LogSink {
        override suspend fun write(event: LogEvent) {
            _lines.update { previous ->
                (listOf(DefaultTextEventCodec.encode(event)) + previous).take(50)
            }
        }
    }
}
