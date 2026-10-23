package io.ilogger.room

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.LogEvent
import io.ilogger.core.Outcome
import io.ilogger.serialization.JsonEventCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoomLogExportTest {

    private lateinit var holder: TestLogDatabaseHolder

    @Before
    fun setUp() {
        holder = TestLogDatabaseHolder()
    }

    @After
    fun tearDown() {
        holder.close()
    }

    private val cipher = object : RoomLogPayloadCipher {
        override fun encrypt(plaintext: String): String = "enc:" + plaintext.reversed()

        override fun decrypt(ciphertext: String): String =
            ciphertext.removePrefix("enc:").reversed()
    }

    private fun event(id: String, ts: Long, action: String = "task.created"): LogEvent = LogEvent(
        id = id,
        timestampMillis = ts,
        level = LogLevel.AUDIT,
        action = action,
        actionVersion = 2,
        actor = Actor.user("u1", "Grace"),
        entity = "Task",
        entityId = "42",
        metadata = mapOf("title" to "Write docs"),
        source = "TaskDao.insert",
        category = "audit",
        correlationId = "c1",
        causationId = "parent-1",
        outcome = Outcome.SUCCESS,
    )

    @Test
    fun `export writes matching rows oldest first as JSONL`() = runBlocking {
        holder.dao.insertAll(
            listOf(
                event("e2", ts = 2_000, action = "task.updated").toRoomLogEntity(),
                event("e1", ts = 1_000).toRoomLogEntity(),
            ),
        )

        val out = StringBuilder()
        val written = holder.dao.exportJsonLines(out, RoomLogFilter(actions = setOf("task.created")))

        assertEquals(1, written)
        val lines = out.toString().trimEnd().lines()
        assertEquals(1, lines.size)
        assertEquals("e1", Json.parseToJsonElement(lines.single()).jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `import projects flat columns and keeps the payload verbatim`() = runBlocking {
        val line = JsonEventCodec.encode(event("e1", ts = 1_000))
        val exported = StringBuilder().append(line).append('\n')
        holder.close()
        holder = TestLogDatabaseHolder()

        val imported = holder.dao.importJsonLines(exported.toString().reader().buffered())

        assertEquals(1, imported)
        val row = holder.dao.query().single()
        assertEquals("e1", row.eventId)
        assertEquals(1_000, row.ts)
        assertEquals("AUDIT", row.level)
        assertEquals("task.created", row.action)
        assertEquals(2, row.actionVersion)
        assertEquals("Task", row.entity)
        assertEquals("42", row.entityId)
        assertEquals("u1", row.actorId)
        assertEquals("SUCCESS", row.outcome)
        assertEquals("TaskDao.insert", row.source)
        assertEquals("audit", row.category)
        assertEquals("c1", row.correlationId)
        assertEquals("parent-1", row.causationId)
        assertEquals(line, row.payload)
    }

    @Test
    fun `import is idempotent on the event id`() = runBlocking {
        val line = JsonEventCodec.encode(event("e1", ts = 1_000))
        val reader = { line.reader().buffered() }

        assertEquals(1, holder.dao.importJsonLines(reader()))
        assertEquals(1, holder.dao.importJsonLines(reader()))
        assertEquals(1, holder.dao.count())
    }

    @Test
    fun `import fails with the line number for malformed input`() = runBlocking {
        val valid = JsonEventCodec.encode(event("e1", ts = 1_000))
        val failure = runCatching {
            holder.dao.importJsonLines("$valid\nnot-json\n".reader().buffered())
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Line 2"))
    }

    @Test
    fun `cipher round trips through the target export and import`() = runBlocking {
        val target = RoomLogTarget(codec = JsonEventCodec, payloadCipher = cipher)
        target.bind(holder.dao)
        target.write(event("e1", ts = 1_000))

        val stored = holder.dao.query().single()
        assertTrue(stored.payload.startsWith("enc:"))

        val out = StringBuilder()
        assertEquals(1, holder.dao.exportJsonLines(out, cipher = cipher))
        assertEquals("e1", Json.parseToJsonElement(out.toString().trim()).jsonObject["id"]?.jsonPrimitive?.content)

        val plaintextLine = out.toString().trim()
        holder.close()
        holder = TestLogDatabaseHolder()
        assertEquals(1, holder.dao.importJsonLines(plaintextLine.reader().buffered(), cipher = cipher))
        assertTrue(holder.dao.query().single().payload.startsWith("enc:"))
    }

    @Test
    fun `export without the cipher rejects encrypted payloads`() = runBlocking {
        val target = RoomLogTarget(codec = JsonEventCodec, payloadCipher = cipher)
        target.bind(holder.dao)
        target.write(event("e1", ts = 1_000))

        val failure = runCatching { holder.dao.exportJsonLines(StringBuilder()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("RoomLogPayloadCipher"))
    }
}
