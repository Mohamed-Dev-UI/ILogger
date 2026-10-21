package io.ilogger.room

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.FieldChange
import io.ilogger.core.LogEvent
import io.ilogger.core.sink.EventCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLogMappingTest {

    @Test
    fun `maps identity semantics and context into flat columns`() {
        val event = LogEvent(
            id = "e1",
            timestampMillis = 1_000,
            level = LogLevel.AUDIT,
            action = "task.created",
            actionVersion = 2,
            actor = Actor.user("u1"),
            entity = "Task",
            entityId = "42",
            source = "TaskDao.insert",
            category = "db",
            correlationId = "c1",
            causationId = "c0",
        )

        val row = event.toRoomLogEntity(EventCodec { "payload" })

        assertEquals("e1", row.eventId)
        assertEquals(1_000L, row.ts)
        assertEquals("AUDIT", row.level)
        assertEquals("task.created", row.action)
        assertEquals(2, row.actionVersion)
        assertEquals("u1", row.actorId)
        assertEquals("Task", row.entity)
        assertEquals("42", row.entityId)
        assertEquals("SUCCESS", row.outcome)
        assertEquals("db", row.category)
        assertEquals("c1", row.correlationId)
        assertEquals("c0", row.causationId)
        assertEquals("payload", row.payload)
    }

    @Test
    fun `default codec stores a lossless json payload`() {
        val event = LogEvent(
            id = "e1",
            timestampMillis = 1,
            level = LogLevel.AUDIT,
            action = "task.created",
            metadata = mapOf("title" to "audit everything"),
            changes = listOf(FieldChange("completed", false, true)),
        )

        val payload = event.toRoomLogEntity().payload

        assertTrue(payload.contains("\"action\":\"task.created\""))
        assertTrue(payload.contains("\"title\""))
        assertTrue(payload.contains("\"completed\""))
    }

    @Test
    fun `schema sql declares every mapped column and index`() {
        val sql = RoomLogSchema.CREATE_TABLE_SQL
        for (column in listOf(
            "eventId", "ts", "level", "action", "actionVersion", "entity",
            "entityId", "actorId", "outcome", "source", "category", "correlationId",
            "causationId", "payload",
        )) {
            assertTrue("missing $column in $sql", sql.contains("`$column`"))
        }
        assertEquals(
            listOf(
                "CREATE INDEX IF NOT EXISTS `index_ilogger_events_ts` ON `ilogger_events` (`ts`)",
                "CREATE INDEX IF NOT EXISTS `index_ilogger_events_level` ON `ilogger_events` (`level`)",
                "CREATE INDEX IF NOT EXISTS `index_ilogger_events_action` ON `ilogger_events` (`action`)",
            ),
            RoomLogSchema.INDEX_SQL,
        )
    }
}
