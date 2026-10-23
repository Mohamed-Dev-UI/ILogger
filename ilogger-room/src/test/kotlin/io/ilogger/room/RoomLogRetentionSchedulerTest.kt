package io.ilogger.room

import io.ilogger.core.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class RoomLogRetentionSchedulerTest {

    private lateinit var holder: TestLogDatabaseHolder

    @Before
    fun setUp() {
        holder = TestLogDatabaseHolder()
    }

    @After
    fun tearDown() {
        holder.close()
    }

    private fun row(id: String, ts: Long): RoomLogEntity = RoomLogEntity(
        eventId = id,
        ts = ts,
        level = "AUDIT",
        action = "task.created",
        actionVersion = 1,
        entity = null,
        entityId = null,
        actorId = null,
        outcome = "SUCCESS",
        source = null,
        category = null,
        correlationId = null,
        causationId = null,
        payload = "{}",
    )

    @Test
    fun `sweep purges rows older than the policy`() = runBlocking {
        holder.dao.insertAll(listOf(row("old", ts = 1_000), row("new", ts = 10_000)))
        val scheduler = RoomLogRetentionScheduler(
            dao = holder.dao,
            retention = RoomLogRetention(keepMillis = 5_000, sweepEveryMillis = 1_000),
            clock = FixedClock(10_000),
        )

        val removed = scheduler.sweep()

        assertEquals(1, removed)
        assertEquals(listOf("new"), holder.dao.query().map { it.eventId })
    }

    @Test
    fun `enforcer constructor sweeps the configured policy`() = runBlocking {
        holder.dao.insertAll(listOf(row("old", ts = 1_000), row("new", ts = 10_000)))
        val enforcer = RoomLogRetentionEnforcer(
            dao = holder.dao,
            policy = RoomLogRetentionPolicy(defaultKeepMillis = 5_000),
            clock = FixedClock(10_000),
        )
        val scheduler = RoomLogRetentionScheduler(enforcer, sweepEveryMillis = 1_000)

        assertEquals(1, scheduler.sweep())
        assertEquals(listOf("new"), holder.dao.query().map { it.eventId })
    }

    @Test
    fun `start sweeps immediately and then per retention interval`() = runTest {
        var sweeps = 0
        val dao = object : RoomLogDao by FakeRoomLogDao() {
            override suspend fun purgeBefore(cutoff: Long): Int {
                sweeps++
                return 0
            }
        }
        val scheduler = RoomLogRetentionScheduler(
            dao = dao,
            retention = RoomLogRetention(keepMillis = 1_000, sweepEveryMillis = 1_000),
            clock = FixedClock(10_000),
        )

        val job = scheduler.start(this)
        runCurrent()
        assertEquals(1, sweeps)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, sweeps)

        job.cancel()
    }

    @Test
    fun `errors are contained and the loop keeps sweeping`() = runTest {
        var attempts = 0
        val dao = object : RoomLogDao by FakeRoomLogDao() {
            override suspend fun purgeBefore(cutoff: Long): Int {
                attempts++
                throw IllegalStateException("database locked")
            }
        }
        val errors = mutableListOf<Throwable>()
        val scheduler = RoomLogRetentionScheduler(
            dao = dao,
            retention = RoomLogRetention(keepMillis = 1_000, sweepEveryMillis = 1_000),
            clock = FixedClock(10_000),
            onError = { errors += it },
        )

        val job = scheduler.start(this)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, errors.size)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(2, errors.size)

        job.cancel()
    }

    @Test
    fun `throwing error handler does not cancel the loop`() = runTest {
        var attempts = 0
        val dao = object : RoomLogDao by FakeRoomLogDao() {
            override suspend fun purgeBefore(cutoff: Long): Int {
                attempts++
                throw IllegalStateException("database locked")
            }
        }
        val scheduler = RoomLogRetentionScheduler(
            dao = dao,
            retention = RoomLogRetention(keepMillis = 1_000, sweepEveryMillis = 1_000),
            clock = FixedClock(10_000),
            onError = { throw IllegalStateException("handler failed") },
        )

        val job = scheduler.start(this)
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(2, attempts)
        assertTrue(job.isActive)
        job.cancel()
    }
}
