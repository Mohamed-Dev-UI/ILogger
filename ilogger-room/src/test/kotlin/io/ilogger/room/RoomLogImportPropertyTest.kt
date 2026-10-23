package io.ilogger.room

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.LogEvent
import io.ilogger.core.Outcome
import java.io.BufferedReader
import java.io.StringReader
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seeded property-style coverage for the JSONL parser: any event the codec
 * produces must export and re-import losslessly, and arbitrary non-object
 * lines must fail with their line number rather than corrupt the table.
 */
class RoomLogImportPropertyTest {

    private val random = Random(20261022L)

    private val fragments = listOf(
        "a", "Z", "0", " ", "-", "_", ".", ":", "\"", "\\", "/", "\n",
        "é", "ñ", "漢", "🚀", "'", ";", "%", "=",
    )

    private fun randomText(maxLength: Int = 12): String = buildString {
        repeat(random.nextInt(0, maxLength + 1)) { append(fragments.random(random)) }
    }

    private fun randomEvent(index: Int): LogEvent {
        val actionSuffix = randomText(10).ifBlank { "x" }
        return LogEvent(
            id = "import-$index",
            timestampMillis = random.nextLong(0, 2_000_000_000_000),
            level = LogLevel.entries.random(random),
            action = "import.$actionSuffix",
            actionVersion = random.nextInt(1, 6),
            actor = if (random.nextBoolean()) Actor.user("actor-$index") else null,
            entity = if (random.nextBoolean()) randomText(8) else null,
            entityId = if (random.nextBoolean()) randomText(8) else null,
            metadata = mapOf("note" to randomText(), "number" to random.nextInt()),
            source = if (random.nextBoolean()) randomText(8) else null,
            category = if (random.nextBoolean()) randomText(8) else null,
            correlationId = if (random.nextBoolean()) randomText(10) else null,
            causationId = if (random.nextBoolean()) randomText(10) else null,
            outcome = Outcome.entries.random(random),
        )
    }

    @Test
    fun `random events survive export and import without changing payloads`() = runBlocking {
        TestLogDatabaseHolder().use { source ->
            TestLogDatabaseHolder().use { target ->
                val events = List(60) { randomEvent(it) }
                source.dao.insertAll(events.map { it.toRoomLogEntity() })

                val text = StringBuilder()
                val exported = source.dao.exportJsonLines(text)
                assertEquals(events.size, exported)

                val imported = target.dao.importJsonLines(BufferedReader(StringReader(text.toString())))
                assertEquals(events.size, imported)

                val order = compareBy<RoomLogEntity>({ it.ts }, { it.eventId })
                val sourceRows = source.dao.query(page = RoomLogPage(limit = events.size)).sortedWith(order)
                val targetRows = target.dao.query(page = RoomLogPage(limit = events.size)).sortedWith(order)

                assertEquals(sourceRows.map { it.eventId }, targetRows.map { it.eventId })
                assertEquals(
                    sourceRows.associate { it.eventId to it.payload },
                    targetRows.associate { it.eventId to it.payload },
                )
                events.forEach { event ->
                    val row = targetRows.first { it.eventId == event.id }
                    assertEquals(event.timestampMillis, row.ts)
                    assertEquals(event.action, row.action)
                    assertEquals(event.level.name, row.level)
                    assertEquals(event.outcome.name, row.outcome)
                    assertEquals(event.actionVersion, row.actionVersion)
                    assertEquals(event.actor?.id, row.actorId)
                    assertEquals(event.entity, row.entity)
                    assertEquals(event.entityId, row.entityId)
                    assertEquals(event.source, row.source)
                    assertEquals(event.category, row.category)
                    assertEquals(event.correlationId, row.correlationId)
                    assertEquals(event.causationId, row.causationId)
                }
            }
        }
    }

    @Test
    fun `arbitrary non-object lines are rejected with line one`() = runBlocking {
        TestLogDatabaseHolder().use { database ->
            repeat(40) { index ->
                val garbage = randomText(20).ifBlank { "x" }

                val error = runCatching {
                    database.dao.importJsonLines(BufferedReader(StringReader(garbage + "\n")))
                }.exceptionOrNull()

                assertTrue("line '$garbage' should be rejected, got $error", error is IllegalArgumentException)
                assertTrue(
                    "line '$garbage' error should name line 1: ${error!!.message}",
                    error.message!!.startsWith("Line 1"),
                )
            }
            assertEquals(0, database.dao.count())
        }
    }

    @Test
    fun `a later malformed line reports its actual line number`() = runBlocking {
        TestLogDatabaseHolder().use { database ->
            val validLine = io.ilogger.serialization.JsonEventCodec.encode(
                LogEvent(id = "valid", timestampMillis = 1, level = LogLevel.AUDIT, action = "valid.event"),
            )

            val error = runCatching {
                database.dao.importJsonLines(
                    BufferedReader(StringReader(validLine + "\nnot-json\n")),
                    batchSize = 1,
                )
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message!!.startsWith("Line 2"))
            assertEquals(1, database.dao.count())
            assertEquals("valid", database.dao.query().single().eventId)
        }
    }
}
