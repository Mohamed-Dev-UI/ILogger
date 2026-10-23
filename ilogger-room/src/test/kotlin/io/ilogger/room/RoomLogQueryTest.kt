package io.ilogger.room

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RoomLogQueryTest {

    private lateinit var holder: TestLogDatabaseHolder
    private val dao: RoomLogDao get() = holder.dao

    @Before
    fun setUp() {
        holder = TestLogDatabaseHolder()
    }

    @After
    fun tearDown() {
        holder.close()
    }

    private fun row(
        id: String,
        ts: Long = 1_000,
        level: LogLevel = LogLevel.AUDIT,
        action: String = "task.created",
        entity: String? = "Task",
        entityId: String? = id,
        actorId: String? = "u1",
        outcome: Outcome = Outcome.SUCCESS,
        correlationId: String? = "c1",
    ): RoomLogEntity = RoomLogEntity(
        eventId = id,
        ts = ts,
        level = level.name,
        action = action,
        actionVersion = 1,
        entity = entity,
        entityId = entityId,
        actorId = actorId,
        outcome = outcome.name,
        source = "test",
        category = "audit",
        correlationId = correlationId,
        causationId = null,
        payload = """{"id":"$id"}""",
    )

    private fun seed() {
        runBlocking {
            dao.insertAll(
                listOf(
                    row("e1", ts = 1_000, level = LogLevel.AUDIT, action = "task.created"),
                    row("e2", ts = 2_000, level = LogLevel.INFO, action = "task.updated", outcome = Outcome.FAILURE, actorId = "u2", correlationId = "c2"),
                    row("e3", ts = 3_000, level = LogLevel.ERROR, action = "billing.charged", entity = "Invoice", entityId = "9", outcome = Outcome.FAILURE),
                    row("e4", ts = 4_000, level = LogLevel.AUDIT, action = "task.deleted", correlationId = "c2"),
                ),
            )
        }
    }

    @Test
    fun `filters by inclusive time range and levels`() = runBlocking {
        seed()

        val inRange = dao.query(RoomLogFilter(fromMillis = 1_500, toMillis = 3_000))
        assertEquals(listOf("e3", "e2"), inRange.map { it.eventId })

        val levels = dao.query(RoomLogFilter(levels = setOf(LogLevel.INFO, LogLevel.ERROR)))
        assertEquals(listOf("e3", "e2"), levels.map { it.eventId })

        val inclusive = dao.query(RoomLogFilter(fromMillis = 1_000, toMillis = 1_000))
        assertEquals(listOf("e1"), inclusive.map { it.eventId })
    }

    @Test
    fun `filters combine action entity entityId actor outcome and correlation`() = runBlocking {
        seed()

        assertEquals(
            listOf("e4", "e1"),
            dao.query(RoomLogFilter(actions = setOf("task.created", "task.deleted"))).map { it.eventId },
        )
        assertEquals(
            listOf("e4", "e1"),
            dao.query(RoomLogFilter(entities = setOf("Task"), entityIds = setOf("e1", "e4"))).map { it.eventId },
        )
        assertEquals(
            listOf("e3"),
            dao.query(RoomLogFilter(actorIds = setOf("u1"), outcomes = setOf(Outcome.FAILURE))).map { it.eventId },
        )
        assertEquals(
            listOf("e4", "e2"),
            dao.query(RoomLogFilter(correlationIds = setOf("c2"))).map { it.eventId },
        )
        assertEquals(
            listOf("e3"),
            dao.query(
                RoomLogFilter(
                    fromMillis = 2_500,
                    levels = setOf(LogLevel.ERROR),
                    actions = setOf("billing.charged"),
                    entities = setOf("Invoice"),
                    entityIds = setOf("9"),
                    actorIds = setOf("u1"),
                    outcomes = setOf(Outcome.FAILURE),
                    correlationIds = setOf("c1"),
                ),
            ).map { it.eventId },
        )
    }

    @Test
    fun `paging is stable for equal timestamps and ordered newest first`() = runBlocking {
        dao.insertAll((1..5).map { row("e$it", ts = 5_000) })

        val first = dao.query(page = RoomLogPage(limit = 2))
        val second = dao.query(page = RoomLogPage(limit = 2, offset = 2))
        val third = dao.query(page = RoomLogPage(limit = 2, offset = 4))

        assertEquals(listOf("e5", "e4"), first.map { it.eventId })
        assertEquals(listOf("e3", "e2"), second.map { it.eventId })
        assertEquals(listOf("e1"), third.map { it.eventId })
    }

    @Test
    fun `oldest first order reverses the tie breaker`() = runBlocking {
        dao.insertAll((1..3).map { row("e$it", ts = 5_000) })

        val rows = dao.query(page = RoomLogPage(limit = 2, order = RoomLogOrder.OLDEST_FIRST))

        assertEquals(listOf("e1", "e2"), rows.map { it.eventId })
    }

    @Test
    fun `count and distinct helpers reflect the filter`() = runBlocking {
        seed()

        assertEquals(4, dao.count())
        assertEquals(3, dao.count(RoomLogFilter(actions = setOf("task.created", "task.updated", "task.deleted"))))
        assertEquals(2, dao.count(RoomLogFilter(outcomes = setOf(Outcome.FAILURE))))

        assertEquals(listOf("billing.charged", "task.created", "task.deleted", "task.updated"), dao.distinctValues(RoomLogFacet.ACTION))
        assertEquals(listOf("Invoice", "Task"), dao.distinctValues(RoomLogFacet.ENTITY))
        assertEquals(listOf("u1", "u2"), dao.distinctValues(RoomLogFacet.ACTOR))
        assertEquals(listOf("c1", "c2"), dao.distinctValues(RoomLogFacet.CORRELATION_ID))
        assertEquals(listOf("AUDIT", "ERROR", "INFO"), dao.distinctValues(RoomLogFacet.LEVEL))
    }

    @Test
    fun `observe emits rows when the table changes`() = runBlocking {
        val filter = RoomLogFilter(actions = setOf("task.created"))
        val emission = async(Dispatchers.Default) {
            withTimeout(10_000) {
                dao.observe(filter, RoomLogPage(limit = 10)).first { it.isNotEmpty() }
            }
        }
        delay(200)
        dao.insertAll(listOf(row("e1", action = "task.created"), row("e2", action = "other")))

        assertEquals(listOf("e1"), emission.await().map { it.eventId })
    }

    @Test
    fun `observeCount emits the filtered count when the table changes`() = runBlocking {
        val filter = RoomLogFilter(outcomes = setOf(Outcome.FAILURE))
        val emission = async(Dispatchers.Default) {
            withTimeout(10_000) {
                dao.observeCount(filter).first { it == 1L }
            }
        }
        delay(200)
        dao.insertAll(
            listOf(
                row("e1", outcome = Outcome.FAILURE),
                row("e2", outcome = Outcome.SUCCESS),
            ),
        )

        assertEquals(1L, emission.await())
    }

    @Test
    fun `page validates limit and offset`() {
        val badLimit = runCatching { RoomLogPage(limit = 0) }.exceptionOrNull()
        val badOffset = runCatching { RoomLogPage(offset = -1) }.exceptionOrNull()

        assertEquals(IllegalArgumentException::class.java, badLimit?.javaClass)
        assertEquals(IllegalArgumentException::class.java, badOffset?.javaClass)
    }
}
