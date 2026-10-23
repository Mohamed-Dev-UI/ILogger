package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ValueAdapterRegistryTest {

    data class Badge(val code: Int)
    data class Point(val x: Int, val y: Int)

    private fun logger(sink: RecordingSink, configure: ILoggerBuilder.() -> Unit = {}): ILogger =
        ILogger.create {
            dispatch = DispatchMode.INLINE
            sink(sink)
            configure()
        }

    @Test
    fun `renders by exact runtime class and passes others through`() {
        val registry = ValueAdapterRegistry()
        registry.register(Badge::class) { it.code }
        val other = Point(1, 2)

        assertEquals(7, registry.render(Badge(7)))
        assertSame(other, registry.render(other))
        assertNull(registry.render(null))
        assertTrue(!registry.isEmpty)
    }

    @Test
    fun `empty registry passes every value through`() {
        val registry = ValueAdapterRegistry()
        val value = Badge(1)
        assertTrue(registry.isEmpty)
        assertSame(value, registry.render(value))
    }

    @Test
    fun `throwing adapter degrades the value to null`() {
        val registry = ValueAdapterRegistry()
        registry.register(Badge::class) { error("boom") }
        assertNull(registry.render(Badge(1)))
    }

    @Test
    fun `builder adapters apply to metadata and changes at materialization`() = runTest {
        val sink = RecordingSink()
        logger(sink) {
            valueAdapter(Badge::class) { it.code }
            valueAdapter(Point::class) { "${it.x},${it.y}" }
        }.use {
            it.record(
                action = "badge.scanned",
                level = LogLevel.INFO,
                metadata = mapOf(
                    "badge" to Badge(9),
                    "point" to Point(3, 4),
                    "plain" to "keep",
                ),
                changes = listOf(FieldChange(field = "badge", before = Badge(1), after = Badge(2))),
            )

            val event = sink.events.single()
            assertEquals(9, event.metadata["badge"])
            assertEquals("3,4", event.metadata["point"])
            assertEquals("keep", event.metadata["plain"])
            assertEquals(1, event.changes.single().before)
            assertEquals(2, event.changes.single().after)
        }
    }

    @Test
    fun `suspend record path applies adapters too`() = runTest {
        val sink = RecordingSink()
        logger(sink) {
            valueAdapter(Badge::class) { it.code }
        }.use {
            it.record(action = "badge.async", metadata = mapOf("badge" to Badge(5)))
            assertEquals(5, sink.events.single().metadata["badge"])
        }
    }

    @Test
    fun `unregistered logger leaves domain values untouched`() = runTest {
        val sink = RecordingSink()
        logger(sink).use {
            it.record(action = "badge.raw", metadata = mapOf("badge" to Badge(3)))
            assertEquals(Badge(3), sink.events.single().metadata["badge"])
        }
    }
}
