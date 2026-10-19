package example.customsink

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.ILogger
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import io.ilogger.core.sink.ConsoleLogSink
import io.ilogger.core.sink.TextFileSink
import io.ilogger.serialization.JsonEventCodec
import java.nio.file.Files
import java.nio.file.Path

/**
 * Own sink implementation: reacts to high-signal events. `LogSink` is a small
 * interface — implement `write` (suspend), optionally override `writeAll`,
 * `flush`, `close`. Sinks must be safe for concurrent use; the engine already
 * serializes writes per binding.
 */
class AlertSink(
    private val react: (LogEvent) -> Unit,
) : LogSink {

    override suspend fun write(event: LogEvent) {
        when (event.level) {
            LogLevel.WARN, LogLevel.ERROR, LogLevel.AUDIT -> react(event)
            else -> Unit
        }
    }
}

fun main() = kotlinx.coroutines.runBlocking {
    val jsonlFile = Files.createTempDirectory("ilogger-custom").resolve("audit.jsonl")

    val logger = ILogger.create {
        dispatch = DispatchMode.INLINE

        // 1. Machine consumption: JSONL file, but ONLY the audit category.
        //    (Category routing keeps private diagnostic noise out of durable logs.)
        sink(
            TextFileSink(
                file = jsonlFile,
                codec = JsonEventCodec,
                append = true,
                flushEveryWrite = true,
                rotation = TextFileSink.Rotation(maxBytesPerFile = 64 * 1024, maxFiles = 5),
            ),
            category = "audit",
        )

        // 2. Human consumption: console catch-all.
        sink(ConsoleLogSink())

        // 3. Reactive consumption: hand-written sink reacts to high-signal events.
        sink(AlertSink { event ->
            println("   ⏰ ALERT ${event.schemaId} outcome=${event.outcome} entity=${event.entity}")
        })
    }
    logger.use {
        logger.info("service starting", "port" to 8080, "dryRun" to false)
        logger.debug("cold path detail", "cacheSize" to 0)

        // Audit events carry the "audit" category — they go to the JSONL file,
        // the console and trigger an alert.
        logger.record(
            action = "order.placed",
            entity = "Order",
            entityId = "o-1001",
            category = "audit",
            metadata = mapOf("total" to 42.50, "currency" to "EUR"),
            source = "example.customsink.main",
        )

        logger.warn("disk nearly full")
        logger.error("upstream payment gateway unreachable")

        // Read back what durable JSON consumption received.
        println("--- audit.jsonl (machine-readable, audit category only)")
        println(Files.readString(jsonlFile))
    }
}
