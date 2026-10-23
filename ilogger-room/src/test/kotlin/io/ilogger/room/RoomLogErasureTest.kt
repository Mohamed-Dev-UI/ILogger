package io.ilogger.room

import io.ilogger.core.FixedClock
import io.ilogger.core.crypto.AesGcmCipher
import io.ilogger.core.crypto.EncryptionKey
import io.ilogger.serialization.JsonEventCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private const val ERASE_NOW: Long = 1_000_000_000_000

private fun eventRow(
    id: String,
    actorId: String? = null,
    entity: String? = null,
    entityId: String? = null,
    ts: Long = ERASE_NOW - 1_000,
): RoomLogEntity = RoomLogEntity(
    eventId = id,
    ts = ts,
    level = "AUDIT",
    action = "task.created",
    actionVersion = 1,
    entity = entity,
    entityId = entityId,
    actorId = actorId,
    outcome = "SUCCESS",
    source = null,
    category = null,
    correlationId = null,
    causationId = null,
    payload = """{"id":"$id"}""",
)

class RoomLogErasureTest {

    private lateinit var holder: TestLogDatabaseHolder
    private val clock = FixedClock(ERASE_NOW)

    @Before
    fun setUp() {
        holder = TestLogDatabaseHolder()
    }

    @After
    fun tearDown() {
        holder.close()
    }

    private suspend fun rows(): List<RoomLogEntity> = holder.dao.query()

    private suspend fun audit(report: RoomLogErasureReport): RoomLogEntity =
        checkNotNull(rows().firstOrNull { it.eventId == report.auditEventId }) {
            "audit event ${report.auditEventId} was not stored"
        }

    @Test
    fun `eraseActor removes the subject and writes a pseudonymous audit event`() = runBlocking {
        holder.dao.insertAll(
            listOf(
                eventRow("a1", actorId = "user-1"),
                eventRow("a2", actorId = "user-1"),
                eventRow("a3", actorId = "user-2"),
                eventRow("e1", entity = "Order", entityId = "order-9"),
            ),
        )

        val report = RoomLogErasure(holder.dao, clock = clock)
            .eraseActor("user-1", reason = "GDPR Art. 17", requestId = "DSAR-2026-118")

        assertEquals("actor", report.targetKind)
        assertEquals(2, report.removed)
        assertEquals(0, report.withheld)
        assertEquals(16, report.targetHash.length)
        assertEquals(setOf("a3", "e1", report.auditEventId), rows().map { it.eventId }.toSet())

        val auditRow = audit(report)
        assertEquals(ROOM_LOG_ERASURE_ACTION, auditRow.action)
        assertEquals(ROOM_LOG_ERASURE_CATEGORY, auditRow.category)
        assertNull(auditRow.actorId)
        assertFalse("audit payload must not retain the erased id", auditRow.payload.contains("user-1"))

        val auditEvent = JsonEventCodec.decode(auditRow.payload)
        assertEquals("actor", auditEvent.metadata["targetKind"])
        assertEquals(report.targetHash, auditEvent.metadata["targetHash"])
        assertEquals(2L, (auditEvent.metadata["removed"] as Number).toLong())
        assertEquals(0L, (auditEvent.metadata["withheld"] as Number).toLong())
        assertEquals("GDPR Art. 17", auditEvent.metadata["reason"])
        assertEquals("DSAR-2026-118", auditEvent.metadata["requestId"])
    }

    @Test
    fun `legal hold withholds subject rows and release allows a later erasure`() = runBlocking {
        holder.dao.insertAll(listOf(eventRow("a1", actorId = "user-1"), eventRow("a2", actorId = "user-1")))
        val holds = RoomLogLegalHoldRegistry().apply { hold(RoomLogLegalHold.Actor("user-1")) }
        val erasure = RoomLogErasure(holder.dao, holds = holds, clock = clock)

        val heldReport = erasure.eraseActor("user-1")
        assertEquals(0, heldReport.removed)
        assertEquals(2, heldReport.withheld)
        assertEquals(setOf("a1", "a2", heldReport.auditEventId), rows().map { it.eventId }.toSet())

        holds.release(RoomLogLegalHold.Actor("user-1"))
        val erased = erasure.eraseActor("user-1")
        assertEquals(2, erased.removed)
        assertEquals(0, erased.withheld)
        assertEquals(setOf(heldReport.auditEventId, erased.auditEventId), rows().map { it.eventId }.toSet())
    }

    @Test
    fun `eraseEntity targets one entity instance`() = runBlocking {
        holder.dao.insertAll(
            listOf(
                eventRow("e1", entity = "Order", entityId = "order-9"),
                eventRow("e2", entity = "Order", entityId = "order-9"),
                eventRow("e3", entity = "Order", entityId = "order-10"),
                eventRow("e4", entity = "Invoice", entityId = "order-9"),
            ),
        )

        val report = RoomLogErasure(holder.dao, clock = clock).eraseEntity("Order", "order-9")

        assertEquals("entity", report.targetKind)
        assertEquals(2, report.removed)
        assertEquals(setOf("e3", "e4", report.auditEventId), rows().map { it.eventId }.toSet())
        assertEquals(report.targetHash, audit(report).let { JsonEventCodec.decode(it.payload).metadata["targetHash"] })
    }

    @Test
    fun `entity hold blocks entity erasure`() = runBlocking {
        holder.dao.insertAll(listOf(eventRow("e1", entity = "Order", entityId = "order-9")))
        val holds = RoomLogLegalHoldRegistry().apply { hold(RoomLogLegalHold.Entity("Order", "order-9")) }

        val report = RoomLogErasure(holder.dao, holds = holds, clock = clock).eraseEntity("Order", "order-9")

        assertEquals(0, report.removed)
        assertEquals(1, report.withheld)
        assertNotNull(rows().firstOrNull { it.eventId == "e1" })
    }

    @Test
    fun `erasure request with no matches still records the audit event`() = runBlocking {
        val report = RoomLogErasure(holder.dao, clock = clock).eraseActor("user-missing")

        assertEquals(0, report.removed)
        assertEquals(0, report.withheld)
        assertEquals(listOf(report.auditEventId), rows().map { it.eventId })
    }

    @Test
    fun `audit payload uses the configured cipher`() = runBlocking {
        val key = EncryptionKey("k1", ByteArray(32) { it.toByte() })
        val aes = AesGcmCipher(key)
        val erasure = RoomLogErasure(
            dao = holder.dao,
            clock = clock,
            payloadCipher = AesGcmRoomPayloadCipher(aes),
        )

        val report = erasure.eraseActor("user-1")
        val stored = audit(report).payload

        assertTrue(stored, stored.startsWith("ilogenc1:k1:"))
        val decoded = JsonEventCodec.decode(aes.decrypt(stored))
        assertEquals(ROOM_LOG_ERASURE_ACTION, decoded.action)
        assertFalse(decoded.metadata.values.toString().contains("user-1"))
    }

    @Test
    fun `blank arguments are rejected`() = runBlocking {
        val erasure = RoomLogErasure(holder.dao)
        val invalid = listOf<suspend () -> Unit>(
            { erasure.eraseActor(" ") },
            { erasure.eraseEntity("Order", " ") },
            { erasure.eraseActor("user-1", reason = " ") },
            { erasure.eraseActor("user-1", requestId = "") },
        )
        invalid.forEach { block ->
            try {
                block()
                fail("Expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}
