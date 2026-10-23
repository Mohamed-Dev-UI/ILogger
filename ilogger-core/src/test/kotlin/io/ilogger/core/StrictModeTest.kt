package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StrictModeTest {

    private class Report(val logger: ILogger, val sink: RecordingSink, val drops: List<DroppedEvent>)

    private fun create(
        mode: StrictMode,
        configure: ILoggerBuilder.() -> Unit = {},
    ): Report {
        val sink = RecordingSink()
        val drops = Collections.synchronizedList(ArrayList<DroppedEvent>())
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.INLINE
            strictMode = mode
            onDrop = { drops += it }
            configure()
        }
        return Report(logger, sink, drops)
    }

    private class Opaque(val label: String)

    @Test
    fun `OFF accepts values that are not log-safe`() {
        val report = create(StrictMode.OFF)
        report.logger.use {
            it.recordNow(action = "strict.off", metadata = mapOf("opaque" to Opaque("ok")))
        }
        assertEquals(1, report.sink.count())
        assertTrue(report.drops.isEmpty())
    }

    @Test
    fun `WARN drops unsafe values and reports STRICT_REJECT`() {
        val report = create(StrictMode.WARN)
        report.logger.use {
            it.recordNow(action = "strict.warn", metadata = mapOf("opaque" to Opaque("no")))
        }

        assertEquals(0, report.sink.count())
        val drop = report.drops.single()
        assertEquals(DropReason.STRICT_REJECT, drop.reason)
        assertTrue(drop.error is IllegalArgumentException)
        assertTrue(drop.error!!.message!!.contains("not log-safe"))
        assertEquals(1L, report.logger.stats().droppedByReason[DropReason.STRICT_REJECT])
    }

    @Test
    fun `THROW rejects and throws from every recording entry point`() = runBlocking {
        val report = create(StrictMode.THROW)
        report.logger.use {
            try {
                it.record(action = "strict.throw", metadata = mapOf("opaque" to Opaque("no")))
                fail("record must throw in THROW mode")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.startsWith("ilogger strict:"))
            }

            try {
                it.recordNow(action = "strict.throw", metadata = mapOf("opaque" to Opaque("no")))
                fail("recordNow must throw in THROW mode")
            } catch (_: IllegalArgumentException) {
                // expected
            }

            try {
                it.recordBlocking(action = "strict.throw", metadata = mapOf("opaque" to Opaque("no")))
                fail("recordBlocking must throw in THROW mode")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }

        assertEquals(0, report.sink.count())
        assertEquals(3, report.drops.size)
        assertEquals(3L, report.logger.stats().droppedByReason[DropReason.STRICT_REJECT])
    }

    @Test
    fun `LogSafeValue opts a value into strict mode`() {
        val report = create(StrictMode.WARN)
        report.logger.use {
            it.recordNow(
                action = "strict.safe",
                metadata = mapOf("opaque" to LogSafeValue.of(Opaque("explicit"))),
            )
        }
        assertEquals(1, report.sink.count())
        assertTrue(report.drops.isEmpty())
        val stored = report.sink.events.single().metadata["opaque"]
        assertTrue(stored is LogSafeValue)
    }

    @Test
    fun `long strings are rejected`() {
        val report = create(StrictMode.WARN) { strictMaxStringLength = 8 }
        report.logger.use {
            it.recordNow(action = "len", metadata = mapOf("key" to "abcdefghij"))
        }
        assertEquals(0, report.sink.count())
        assertTrue(report.drops.single().error!!.message!!.contains("strict limit is 8"))
    }

    @Test
    fun `blank metadata keys are rejected`() {
        val report = create(StrictMode.WARN)
        report.logger.use {
            it.recordNow(action = "strict.blank", metadata = mapOf("  " to "value"))
        }
        assertEquals(0, report.sink.count())
        assertTrue(report.drops.single().error!!.message!!.contains("must not be blank"))
    }

    @Test
    fun `entry limits apply to metadata and nested collections`() {
        val report = create(StrictMode.WARN) { strictMaxEntries = 2 }
        report.logger.use {
            it.recordNow(
                action = "strict.entries",
                metadata = mapOf("one" to 1, "two" to 2, "three" to 3),
            )
            it.recordNow(
                action = "strict.nested",
                metadata = mapOf("list" to listOf("a", "b", "c")),
            )
        }
        assertEquals(0, report.sink.count())
        assertEquals(2, report.drops.size)
        assertTrue(report.drops.all { it.error!!.message!!.contains("strict limit is 2") })
    }

    @Test
    fun `log-safe nested collections are accepted`() {
        val report = create(StrictMode.WARN)
        report.logger.use {
            it.recordNow(
                action = "strict.nested.ok",
                metadata = mapOf(
                    "list" to listOf("a", 1, true, null),
                    "map" to mapOf("inner" to listOf(1L, 2.5)),
                    "array" to intArrayOf(1, 2, 3),
                    "enum" to LogLevel.INFO,
                ),
            )
        }
        assertEquals(1, report.sink.count())
        assertTrue(report.drops.isEmpty())
    }

    @Test
    fun `nested unsafe values and non-string map keys are rejected`() {
        val report = create(StrictMode.WARN)
        report.logger.use {
            it.recordNow(
                action = "strict.nested.bad",
                metadata = mapOf("list" to listOf("safe", Opaque("unsafe"))),
            )
            it.recordNow(
                action = "strict.map.bad",
                metadata = mapOf("map" to mapOf(1 to "value")),
            )
        }
        assertEquals(0, report.sink.count())
        assertEquals(2, report.drops.size)
        assertTrue(report.drops[0].error!!.message!!.contains("not log-safe"))
        assertTrue(report.drops[1].error!!.message!!.contains("non-string key"))
    }

    @Test
    fun `actor attributes are validated`() {
        val report = create(StrictMode.WARN) { strictMaxStringLength = 8 }
        report.logger.use {
            it.recordNow(
                action = "act",
                actor = Actor.user("u1", attributes = mapOf("tenant" to "a-very-long-tenant")),
            )
        }
        assertEquals(0, report.sink.count())
        assertTrue(report.drops.single().error!!.message!!.contains("actor.attributes"))
    }

    @Test
    fun `change values are validated`() {
        val report = create(StrictMode.WARN)
        report.logger.use {
            it.recordNow(
                action = "strict.change",
                changes = listOf(FieldChange("title", "old", Opaque("new"))),
            )
        }
        assertEquals(0, report.sink.count())
        assertTrue(report.drops.single().error!!.message!!.contains("changes[title].after"))
    }

    @Test
    fun `the expanded safe value model is accepted`() {
        val report = create(StrictMode.WARN)
        report.logger.use {
            it.recordNow(
                action = "strict.model",
                metadata = mapOf(
                    "uuid" to UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                    "instant" to Instant.parse("2026-01-01T00:00:00Z"),
                    "duration" to Duration.ofHours(2),
                    "decimal" to BigDecimal("19.99"),
                    "unsigned" to 42u,
                    "unsignedLong" to 7uL,
                ),
            )
        }
        assertEquals(1, report.sink.count())
        assertTrue(report.drops.isEmpty())
    }

    @Test
    fun `the byte estimate bounds otherwise valid payloads`() {
        val report = create(StrictMode.WARN) { strictMaxEventBytes = 32 }
        report.logger.use {
            it.recordNow(action = "strict.bytes", metadata = mapOf("key" to "12345678901234567890"))
        }
        assertEquals(0, report.sink.count())
        assertTrue(report.drops.single().error!!.message!!.contains("byte estimate of 32"))

        val small = create(StrictMode.WARN) { strictMaxEventBytes = 64 }
        small.logger.use {
            it.recordNow(action = "strict.bytes", metadata = mapOf("key" to "value"))
        }
        assertEquals(1, small.sink.count())
    }
}
