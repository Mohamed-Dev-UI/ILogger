package io.ilogger.room

import io.ilogger.core.FixedClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private const val NOW: Long = 1_000_000_000_000
private const val DAY: Long = 86_400_000

private fun row(
    id: String,
    ts: Long,
    actorId: String? = null,
    entity: String? = null,
    entityId: String? = null,
    category: String? = null,
    action: String = "task.created",
): RoomLogEntity = RoomLogEntity(
    eventId = id,
    ts = ts,
    level = "AUDIT",
    action = action,
    actionVersion = 1,
    entity = entity,
    entityId = entityId,
    actorId = actorId,
    outcome = "SUCCESS",
    source = null,
    category = category,
    correlationId = null,
    causationId = null,
    payload = """{"id":"$id"}""",
)

class RoomLogRetentionPolicyTest {

    private val policy = RoomLogRetentionPolicy(
        defaultKeepMillis = 30 * DAY,
        rules = listOf(
            RoomLogRetentionRule(keepMillis = 365 * DAY, category = "security"),
            RoomLogRetentionRule(keepMillis = 90 * DAY, entity = "Order"),
            RoomLogRetentionRule(keepMillis = 7 * DAY, category = "debug", entity = "HttpCall"),
        ),
    )

    @Test
    fun `resolution is most specific first then default`() {
        assertEquals(7 * DAY, policy.keepMillisFor("debug", "HttpCall"))
        assertEquals(90 * DAY, policy.keepMillisFor("security", "Order"))
        assertEquals(90 * DAY, policy.keepMillisFor(null, "Order"))
        assertEquals(365 * DAY, policy.keepMillisFor("security", "Invoice"))
        assertEquals(365 * DAY, policy.keepMillisFor("security", null))
        assertEquals(30 * DAY, policy.keepMillisFor("debug", "Other"))
        assertEquals(30 * DAY, policy.keepMillisFor(null, null))
        assertEquals(7 * DAY, policy.minimumKeepMillis())
    }

    @Test
    fun `default null retains unmatched rows forever`() {
        val noDefault = RoomLogRetentionPolicy(
            defaultKeepMillis = null,
            rules = listOf(RoomLogRetentionRule.days(10, entity = "Order")),
        )
        assertNull(noDefault.keepMillisFor("security", null))
        assertNull(noDefault.keepMillisFor(null, null))
        assertEquals(10 * DAY, noDefault.keepMillisFor(null, "Order"))
        assertEquals(10 * DAY, noDefault.minimumKeepMillis())
    }

    @Test
    fun `days factory converts and validates`() {
        val rule = RoomLogRetentionRule.days(14, category = "debug")
        assertEquals(14 * DAY, rule.keepMillis)
        assertEquals("debug", rule.category)
        assertNull(rule.entity)
        assertThrowsIllegalArgument { RoomLogRetentionRule.days(0) }
    }

    @Test
    fun `rule validation rejects missing selectors and invalid values`() {
        assertThrowsIllegalArgument { RoomLogRetentionRule(keepMillis = 10) }
        assertThrowsIllegalArgument { RoomLogRetentionRule(keepMillis = -1, entity = "Order") }
        assertThrowsIllegalArgument { RoomLogRetentionRule(keepMillis = 10, category = " ") }
        assertThrowsIllegalArgument { RoomLogRetentionRule(keepMillis = 10, entity = "") }
    }

    @Test
    fun `policy validation rejects empty and duplicate configurations`() {
        assertThrowsIllegalArgument { RoomLogRetentionPolicy(defaultKeepMillis = null) }
        assertThrowsIllegalArgument {
            RoomLogRetentionPolicy(
                defaultKeepMillis = null,
                rules = listOf(
                    RoomLogRetentionRule(keepMillis = 10, category = "a"),
                    RoomLogRetentionRule(keepMillis = 20, category = "a"),
                ),
            )
        }
        assertThrowsIllegalArgument { RoomLogRetentionPolicy(defaultKeepMillis = -1) }
    }

    @Test
    fun `rule matches rows by configured selectors`() {
        val orderRule = RoomLogRetentionRule(keepMillis = 1, entity = "Order")
        assertTrue(orderRule.matches(row("e", 0, entity = "Order", category = "any")))
        assertFalse(orderRule.matches(row("e", 0, entity = "Invoice")))

        val pairRule = RoomLogRetentionRule(keepMillis = 1, category = "debug", entity = "HttpCall")
        assertTrue(pairRule.matches(row("e", 0, category = "debug", entity = "HttpCall")))
        assertFalse(pairRule.matches(row("e", 0, category = "debug")))
        assertFalse(pairRule.matches(row("e", 0, entity = "HttpCall")))
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}

class RoomLogLegalHoldTest {

    private val subject = row("e1", 0, actorId = "user-42", entity = "Order", entityId = "order-7", category = "security", action = "order.updated")

    @Test
    fun `each hold kind matches its target`() {
        assertTrue(RoomLogLegalHold.Event("e1").matches(subject))
        assertFalse(RoomLogLegalHold.Event("e2").matches(subject))

        assertTrue(RoomLogLegalHold.Actor("user-42").matches(subject))
        assertFalse(RoomLogLegalHold.Actor("user-7").matches(subject))

        assertTrue(RoomLogLegalHold.Entity("Order").matches(subject))
        assertTrue(RoomLogLegalHold.Entity("Order", "order-7").matches(subject))
        assertFalse(RoomLogLegalHold.Entity("Order", "order-8").matches(subject))
        assertFalse(RoomLogLegalHold.Entity("Invoice").matches(subject))

        assertTrue(RoomLogLegalHold.Category("security").matches(subject))
        assertFalse(RoomLogLegalHold.Category("debug").matches(subject))

        assertTrue(RoomLogLegalHold.Action("order.updated").matches(subject))
        assertFalse(RoomLogLegalHold.Action("order.created").matches(subject))
    }

    @Test
    fun `registry supports hold release snapshot and clear`() {
        val registry = RoomLogLegalHoldRegistry()
        val actorHold = RoomLogLegalHold.Actor("user-42")
        registry.hold(actorHold, RoomLogLegalHold.Category("security"))

        assertEquals(2, registry.size)
        assertTrue(registry.isHeld(subject))
        assertTrue(actorHold in registry.snapshot())

        assertTrue(registry.release(actorHold))
        assertFalse(registry.release(actorHold))
        assertTrue(registry.isHeld(subject))

        registry.clear()
        assertFalse(registry.isHeld(subject))
        assertEquals(0, registry.size)
    }

    @Test
    fun `holds validate identifiers`() {
        val invalid = listOf<() -> Unit>(
            { RoomLogLegalHold.Event(" ") },
            { RoomLogLegalHold.Actor("") },
            { RoomLogLegalHold.Entity("") },
            { RoomLogLegalHold.Entity("Order", " ") },
            { RoomLogLegalHold.Category(" ") },
            { RoomLogLegalHold.Action("") },
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

class RoomLogRetentionEnforcerTest {

    private lateinit var holder: TestLogDatabaseHolder
    private val clock = FixedClock(NOW)

    @Before
    fun setUp() {
        holder = TestLogDatabaseHolder()
    }

    @After
    fun tearDown() {
        holder.close()
    }

    private val policy = RoomLogRetentionPolicy(
        defaultKeepMillis = 30 * DAY,
        rules = listOf(
            RoomLogRetentionRule(keepMillis = 365 * DAY, category = "security"),
            RoomLogRetentionRule(keepMillis = 90 * DAY, entity = "Order"),
            RoomLogRetentionRule(keepMillis = 7 * DAY, category = "debug", entity = "HttpCall"),
        ),
    )

    private fun seedRows() {
        runBlocking {
            holder.dao.insertAll(
                listOf(
                    row("old-default", NOW - 40 * DAY),
                    row("new-default", NOW - 1 * DAY),
                    row("old-security", NOW - 40 * DAY, category = "security"),
                    row("old-order", NOW - 100 * DAY, entity = "Order"),
                    row("young-order", NOW - 80 * DAY, entity = "Order"),
                    row("old-debug-http", NOW - 8 * DAY, category = "debug", entity = "HttpCall"),
                    row("new-debug-http", NOW - 3 * DAY, category = "debug", entity = "HttpCall"),
                ),
            )
        }
    }

    private suspend fun ids(): Set<String> = holder.dao.query().map { it.eventId }.toSet()

    @Test
    fun `sweep applies per-category and per-entity windows`() = runBlocking {
        seedRows()
        val sweep = RoomLogRetentionEnforcer(holder.dao, policy, clock = clock).sweep()

        assertEquals(3, sweep.removed)
        assertEquals(0, sweep.held)
        assertEquals(
            setOf("new-default", "old-security", "young-order", "new-debug-http"),
            ids(),
        )
    }

    @Test
    fun `legal hold blocks deletion and is reported`() = runBlocking {
        seedRows()
        holder.dao.insertAll(listOf(row("held-row", NOW - 40 * DAY, actorId = "user-1")))
        val holds = RoomLogLegalHoldRegistry().apply { hold(RoomLogLegalHold.Actor("user-1")) }
        val enforcer = RoomLogRetentionEnforcer(holder.dao, policy, holds = holds, clock = clock)

        val first = enforcer.sweep()
        assertEquals(3, first.removed)
        assertEquals(1, first.held)
        assertTrue("held-row" in ids())

        holds.release(RoomLogLegalHold.Actor("user-1"))
        val second = enforcer.sweep()
        assertEquals(1, second.removed)
        assertEquals(0, second.held)
        assertFalse("held-row" in ids())
    }

    @Test
    fun `beforePurge sees exactly the rows about to be deleted`() = runBlocking {
        seedRows()
        val archived = ArrayList<String>()
        var rowsStillPresent = -1
        val enforcer = RoomLogRetentionEnforcer(
            dao = holder.dao,
            policy = policy,
            clock = clock,
            beforePurge = { rows ->
                archived += rows.map { it.eventId }
                rowsStillPresent = ids().size
            },
        )

        enforcer.sweep()

        assertEquals(setOf("old-default", "old-order", "old-debug-http"), archived.toSet())
        assertEquals(7, rowsStillPresent)
    }

    @Test
    fun `throwing archive hook aborts deletion`() = runBlocking {
        seedRows()
        val enforcer = RoomLogRetentionEnforcer(
            dao = holder.dao,
            policy = policy,
            clock = clock,
            beforePurge = { throw IllegalStateException("archive unavailable") },
        )

        try {
            enforcer.sweep()
            fail("Expected the archive failure")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(7, ids().size)
    }

    @Test
    fun `rows outside the policy are retained forever`() = runBlocking {
        holder.dao.insertAll(
            listOf(
                row("matched-old", NOW - 40 * DAY, entity = "Order"),
                row("unmatched-old", NOW - 4_000 * DAY, category = "security-less"),
            ),
        )
        val policy = RoomLogRetentionPolicy(
            defaultKeepMillis = null,
            rules = listOf(RoomLogRetentionRule(keepMillis = 10 * DAY, entity = "Order")),
        )

        val sweep = RoomLogRetentionEnforcer(holder.dao, policy, clock = clock).sweep()

        assertEquals(1, sweep.removed)
        assertEquals(setOf("unmatched-old"), ids())
    }

    @Test
    fun `sweep pages past more rows than one page holds`() = runBlocking {
        val rows = (1..600).map { index -> row("e$index", NOW - (40 * DAY) - index) }
        holder.dao.insertAll(rows)

        val sweep = RoomLogRetentionEnforcer(holder.dao, policy, clock = clock).sweep()

        assertEquals(600, sweep.removed)
        assertTrue(ids().isEmpty())
    }
}
