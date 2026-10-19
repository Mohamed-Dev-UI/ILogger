package example.basic

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.ErrorMode
import io.ilogger.core.ILogger
import io.ilogger.core.withActor
import io.ilogger.core.withContextAttributes
import io.ilogger.core.sink.ConsoleLogSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
    // Independent logger instances own their pipeline; created with INLINE
    // dispatch here so the demo output is ordered immediately.
    val logger = ILogger.create {
        sink(ConsoleLogSink())
        dispatch = DispatchMode.INLINE
        onErrorMode(LogLevel.AUDIT, ErrorMode.RETRY)
    }
    logger.use {
        // ⇦ the generated extension — one call wraps the whole DAO.
        val dao = InMemoryTaskDao().logged(logger)

        // Ambient context (actor, correlation, attributes) rides the coroutine
        // context: nested operations audit events automatically carry it.
        withActor(Actor.user("u-123", name = "Ada")) {
            withContextAttributes("tenantId" to "acme") {
                val id = dao.insert(Task(id = 7, title = "ship it"))
                dao.rename(Task(id, "ship it"), "shipped")
                dao.delete(Task(id, "shipped"))
            }
        }

        // Manual high-signal events use the same engine (no wrapper needed).
        logger.record(
            action = "app.started",
            level = LogLevel.INFO,
            metadata = mapOf("version" to "0.1.0"),
            source = "example.basic.main",
        )
        delay(10)
    }
}
