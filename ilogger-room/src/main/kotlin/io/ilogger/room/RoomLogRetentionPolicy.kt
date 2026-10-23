package io.ilogger.room

import io.ilogger.core.LogClock
import io.ilogger.core.SystemClock

/**
 * One retention rule for [RoomLogEntity] rows, selected by [category], [entity]
 * or both.
 *
 * A rule with both selectors matches only rows where both columns are equal; a
 * rule with one selector matches every row carrying that value. At least one
 * selector is required — a rule without one would be a default, which belongs
 * on [RoomLogRetentionPolicy.defaultKeepMillis].
 */
public data class RoomLogRetentionRule(
    /** Maximum age of a matching row, in milliseconds. */
    public val keepMillis: Long,
    /** Category value to match, or `null` for "any category". */
    public val category: String? = null,
    /** Entity (type) value to match, or `null` for "any entity". */
    public val entity: String? = null,
) {
    init {
        require(keepMillis >= 0) { "keepMillis must be >= 0, got $keepMillis" }
        require(category != null || entity != null) {
            "a RoomLogRetentionRule must select a category, an entity, or both"
        }
        require(category == null || category.isNotBlank()) { "category must not be blank" }
        require(entity == null || entity.isNotBlank()) { "entity must not be blank" }
    }

    /** Whether [row] falls under this rule. */
    public fun matches(row: RoomLogEntity): Boolean =
        (category == null || row.category == category) &&
            (entity == null || row.entity == entity)

    public companion object {
        /** A rule expressed in days; [days] must be positive. */
        public fun days(days: Int, category: String? = null, entity: String? = null): RoomLogRetentionRule {
            require(days > 0) { "days must be > 0, got $days" }
            return RoomLogRetentionRule(keepMillis = days * 86_400_000L, category = category, entity = entity)
        }
    }
}

/**
 * Per-category and per-entity retention policy for the shipped Room table.
 *
 * Resolution is by specificity: a rule selecting both category and entity wins
 * over an entity rule, which wins over a category rule, which wins over
 * [defaultKeepMillis]. A row matched by no rule and no default is retained
 * forever, so a policy can opt specific categories into retention without
 * agreeing to delete everything else.
 *
 * ```kotlin
 * val policy = RoomLogRetentionPolicy(
 *     defaultKeepMillis = 30 * 86_400_000L,
 *     rules = listOf(
 *         RoomLogRetentionRule.days(365, category = "security"),
 *         RoomLogRetentionRule.days(90, entity = "Order"),
 *         RoomLogRetentionRule.days(7, category = "debug", entity = "HttpCall"),
 *     ),
 * )
 * ```
 *
 * The policy is a value object: validation happens here, enforcement in
 * [RoomLogRetentionEnforcer].
 */
public class RoomLogRetentionPolicy(
    /** Maximum age for rows matched by no rule; `null` keeps them forever. */
    public val defaultKeepMillis: Long?,
    rules: List<RoomLogRetentionRule> = emptyList(),
) {
    /** Immutable copy of the configured rules. */
    public val rules: List<RoomLogRetentionRule> = rules.toList()

    init {
        require(defaultKeepMillis == null || defaultKeepMillis >= 0) {
            "defaultKeepMillis must be >= 0, got $defaultKeepMillis"
        }
        require(defaultKeepMillis != null || this.rules.isNotEmpty()) {
            "a retention policy needs a defaultKeepMillis or at least one rule"
        }
        val selectors = HashSet<Pair<String?, String?>>()
        this.rules.forEach { rule ->
            require(selectors.add(rule.category to rule.entity)) {
                "duplicate retention rule for category=${rule.category}, entity=${rule.entity}"
            }
        }
    }

    /**
     * Keep window for a row with [category] and [entity], or `null` when the
     * row is retained forever. Most specific matching rule wins.
     */
    public fun keepMillisFor(category: String?, entity: String?): Long? {
        if (category != null && entity != null) {
            rules.firstOrNull { it.category == category && it.entity == entity }?.let { return it.keepMillis }
        }
        if (entity != null) {
            rules.firstOrNull { it.category == null && it.entity == entity }?.let { return it.keepMillis }
        }
        if (category != null) {
            rules.firstOrNull { it.category == category && it.entity == null }?.let { return it.keepMillis }
        }
        return defaultKeepMillis
    }

    /** Smallest keep window any row can resolve to; the sweep's candidate cutoff. */
    public fun minimumKeepMillis(): Long {
        val fromRules = rules.minOfOrNull { it.keepMillis }
        return when {
            defaultKeepMillis == null -> requireNotNull(fromRules)
            fromRules == null -> defaultKeepMillis
            else -> minOf(defaultKeepMillis, fromRules)
        }
    }
}

/**
 * Hold-aware retention sweep for [RoomLogDao], scheduled independently of
 * writes with [RoomLogRetentionScheduler].
 *
 * Each sweep pages expired candidates oldest-first (keyset, so held rows never
 * stall the scan), resolves every row through [policy], skips rows matched by
 * [holds], and deletes the rest in chunks. [beforePurge] receives exactly the
 * rows about to be deleted — export or archive there; if it throws, nothing in
 * that chunk is deleted.
 *
 * ```kotlin
 * val enforcer = RoomLogRetentionEnforcer(
 *     dao = db.logDao(),
 *     policy = policy,
 *     holds = holds,
 *     beforePurge = { rows -> archive.write(rows) },
 * )
 * RoomLogRetentionScheduler(enforcer, sweepEveryMillis = 3_600_000).start(appScope)
 * ```
 */
public class RoomLogRetentionEnforcer(
    private val dao: RoomLogDao,
    private val policy: RoomLogRetentionPolicy,
    private val holds: RoomLogLegalHoldRegistry = RoomLogLegalHoldRegistry(),
    private val clock: LogClock = SystemClock,
    private val beforePurge: (suspend (List<RoomLogEntity>) -> Unit)? = null,
) {

    /** Runs one sweep and reports what it deleted and what a hold protected. */
    public suspend fun sweep(): RoomLogRetentionSweep {
        val now = clock.nowMillis()
        val cutoff = now - policy.minimumKeepMillis()
        var afterTs = Long.MIN_VALUE
        var afterId = ""
        var removed = 0
        var held = 0

        while (true) {
            val page = dao.expiredPage(cutoff, afterTs, afterId, SWEEP_PAGE_SIZE)
            if (page.isEmpty()) break

            val expired = ArrayList<RoomLogEntity>(page.size)
            page.forEach { row ->
                val keep = policy.keepMillisFor(row.category, row.entity) ?: return@forEach
                if (row.ts > now - keep) return@forEach
                if (holds.isHeld(row)) {
                    held++
                    return@forEach
                }
                expired += row
            }

            if (expired.isNotEmpty()) {
                beforePurge?.invoke(expired)
                removed += dao.deleteByIds(expired.map { it.eventId })
            }

            val last = page.last()
            afterTs = last.ts
            afterId = last.eventId
        }
        return RoomLogRetentionSweep(removed = removed, held = held)
    }

    private companion object {
        const val SWEEP_PAGE_SIZE: Int = 500
    }
}

/**
 * Outcome of one [RoomLogRetentionEnforcer.sweep].
 *
 * @property removed rows deleted
 * @property held rows that would have been deleted but are protected by a hold
 */
public data class RoomLogRetentionSweep(
    public val removed: Int,
    public val held: Int,
)
