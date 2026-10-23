package io.ilogger.cli

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFilterTest {

    private fun filter(vararg args: String, now: Long = 2_000_000_000_000): EventFilter =
        EventFilter.from(
            Options.parse(args.toList(), FILTER_OPTIONS),
            nowMillis = now,
        )

    @Test
    fun `an empty filter matches everything`() {
        assertTrue(filter().matches(sampleEvent()))
    }

    @Test
    fun `level matching is case insensitive and multi-valued`() {
        val filter = filter("--level", "warn,ERROR")
        assertTrue(filter.matches(sampleEvent(level = LogLevel.WARN)))
        assertTrue(filter.matches(sampleEvent(level = LogLevel.ERROR)))
        assertFalse(filter.matches(sampleEvent(level = LogLevel.AUDIT)))
    }

    @Test
    fun `actions match exactly or by prefix`() {
        val prefix = filter("--action", "task.*")
        assertTrue(prefix.matches(sampleEvent(action = "task.created")))
        assertFalse(prefix.matches(sampleEvent(action = "user.created")))

        val exact = filter("--action", "task.created,user.deleted")
        assertTrue(exact.matches(sampleEvent(action = "user.deleted")))
        assertFalse(exact.matches(sampleEvent(action = "task.deleted")))
    }

    @Test
    fun `entity and actor filters support type-id notation`() {
        val event = sampleEvent(
            entity = "Task",
            actor = Actor.user("u-1", name = "Ada"),
        )
        assertTrue(filter("--entity", "Task").matches(event))
        assertFalse(filter("--entity", "User").matches(event))
        assertTrue(filter("--actor", "u-1").matches(event))
        assertTrue(filter("--actor", "Ada").matches(event))
        assertTrue(filter("--actor", "user:u-1").matches(event))
        assertFalse(filter("--actor", "worker:u-1").matches(event))
        assertFalse(filter("--actor", "u-1").matches(sampleEvent()))
    }

    @Test
    fun `category and source filters match`() {
        val event = sampleEvent(category = "http", source = "TaskDao.insert")
        assertTrue(filter("--category", "http").matches(event))
        assertFalse(filter("--category", "audit").matches(event))
        assertTrue(filter("--source", "TaskDao.*").matches(event))
        assertFalse(filter("--source", "OtherDao.*").matches(event))
    }

    @Test
    fun `time range is inclusive`() {
        val event = sampleEvent(timestampMillis = 1_700_000_000_500)
        assertTrue(filter("--since", "1700000000500").matches(event))
        assertTrue(filter("--until", "1700000000500").matches(event))
        assertFalse(filter("--since", "1700000000501").matches(event))
        assertFalse(filter("--until", "1700000000499").matches(event))

        assertTrue(filter("--since", "2023-11-14T22:13:20Z").matches(event))
        assertTrue(filter("--since", "2023-11-14").matches(event))
    }

    @Test
    fun `last computes the window from now`() {
        val now = 1_700_000_000_000
        val filter = filter("--last", "30m", now = now)
        assertTrue(filter.matches(sampleEvent(timestampMillis = now - 60_000)))
        assertFalse(filter.matches(sampleEvent(timestampMillis = now - 31 * 60_000)))

        val day = filter("--last", "1d", now = now)
        assertTrue(day.matches(sampleEvent(timestampMillis = now - 23 * 3_600_000)))
    }

    @Test
    fun `invalid filters are usage errors`() {
        assertThrows(UsageException::class.java) { filter("--level", "LOUD") }
        assertThrows(UsageException::class.java) { filter("--since", "yesterday") }
        assertThrows(UsageException::class.java) { filter("--last", "30") }
        assertThrows(UsageException::class.java) { filter("--last", "0m") }
        val conflict = assertThrows(UsageException::class.java) {
            filter("--last", "1h", "--since", "100")
        }
        assertTrue(conflict.message!!.contains("cannot be combined"))
        val inverted = assertThrows(UsageException::class.java) {
            filter("--since", "200", "--until", "100")
        }
        assertTrue(inverted.message!!.contains("must not be later"))
    }

    private companion object {
        val FILTER_OPTIONS = setOf(
            "level", "action", "entity", "actor", "category", "source",
            "since", "until", "last",
        )
    }
}
