package io.ilogger.room

import androidx.room.RoomRawQuery
import io.ilogger.annotations.LogLevel
import io.ilogger.core.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Typed predicate over [RoomLogEntity] rows.
 *
 * Every property is optional; an empty set (or `null` timestamp) means "no
 * constraint". Multiple properties combine with `AND`, values inside a set
 * combine with `IN` (`OR`). Timestamps are inclusive:
 * `fromMillis <= ts <= toMillis`.
 *
 * All values are bound as SQL parameters — filter values never become part of
 * the SQL text.
 *
 * ```kotlin
 * val failedCheckouts = logDao.query(
 *     RoomLogFilter(
 *         fromMillis = sinceMillis,
 *         levels = setOf(LogLevel.AUDIT),
 *         actions = setOf("order.checkout"),
 *         outcomes = setOf(Outcome.FAILURE),
 *     ),
 *     RoomLogPage(limit = 50),
 * )
 * ```
 */
public data class RoomLogFilter(
    /** Inclusive lower bound on `ts` (epoch millis). */
    public val fromMillis: Long? = null,
    /** Inclusive upper bound on `ts` (epoch millis). */
    public val toMillis: Long? = null,
    public val levels: Set<LogLevel> = emptySet(),
    public val actions: Set<String> = emptySet(),
    /** Values of the `entity` column (the entity type, e.g. `Order`). */
    public val entities: Set<String> = emptySet(),
    public val entityIds: Set<String> = emptySet(),
    public val actorIds: Set<String> = emptySet(),
    public val outcomes: Set<Outcome> = emptySet(),
    public val correlationIds: Set<String> = emptySet(),
)

/** Row order for paged queries. The event id breaks ties on equal timestamps. */
public enum class RoomLogOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
}

/** Limit/offset window over a filtered result set. */
public data class RoomLogPage(
    public val limit: Int = 100,
    public val offset: Int = 0,
    public val order: RoomLogOrder = RoomLogOrder.NEWEST_FIRST,
) {
    init {
        require(limit > 0) { "limit must be > 0, got $limit" }
        require(offset >= 0) { "offset must be >= 0, got $offset" }
    }
}

/** Single-column `COUNT(*)` result of [count] / [observeCount]. */
public data class RoomLogCount(
    public val value: Long,
)

/** Single-column result of [RoomLogDao.distinctRaw] / [distinctValues]. */
public data class RoomLogValue(
    public val value: String,
)

/** Columns exposed by [distinctValues]. */
public enum class RoomLogFacet {
    LEVEL,
    ACTION,
    ENTITY,
    ENTITY_ID,
    ACTOR,
    OUTCOME,
    SOURCE,
    CATEGORY,
    CORRELATION_ID,
}

// ---------------------------------------------------------------------------
// Typed helpers
// ---------------------------------------------------------------------------

/**
 * Reads one page of rows matching [filter], newest first by default.
 *
 * Use [observe] instead to follow changes as a [Flow].
 */
public suspend fun RoomLogDao.query(
    filter: RoomLogFilter = RoomLogFilter(),
    page: RoomLogPage = RoomLogPage(),
): List<RoomLogEntity> = queryRaw(page.rawQuery(filter))

/**
 * Observes one page of rows matching [filter]; emits again whenever the
 * `ilogger_events` table changes.
 */
public fun RoomLogDao.observe(
    filter: RoomLogFilter = RoomLogFilter(),
    page: RoomLogPage = RoomLogPage(),
): Flow<List<RoomLogEntity>> = observeRaw(page.rawQuery(filter))

/** Number of rows matching [filter]. */
public suspend fun RoomLogDao.count(
    filter: RoomLogFilter = RoomLogFilter(),
): Long = countRaw(countRawQuery(filter)).firstOrNull()?.value ?: 0L

/**
 * Observes the number of rows matching [filter]; emits again whenever the
 * table changes.
 */
public fun RoomLogDao.observeCount(
    filter: RoomLogFilter = RoomLogFilter(),
): Flow<Long> = observeCountRaw(countRawQuery(filter)).map { rows -> rows.firstOrNull()?.value ?: 0L }

/**
 * Distinct non-null values of [facet] among rows matching [filter], sorted
 * ascending. Useful for filter dropdowns in an audit viewer.
 */
public suspend fun RoomLogDao.distinctValues(
    facet: RoomLogFacet,
    filter: RoomLogFilter = RoomLogFilter(),
): List<String> = distinctRaw(distinctRawQuery(facet, filter)).map { it.value }

// ---------------------------------------------------------------------------
// SQL construction
// ---------------------------------------------------------------------------

private class SqlWhere(
    val clause: String,
    val args: List<Any>,
)

private fun RoomLogFilter.buildWhere(): SqlWhere {
    val clauses = ArrayList<String>(9)
    val args = ArrayList<Any>(24)

    fun <T> addSet(column: String, values: Set<T>, text: (T) -> String) {
        if (values.isEmpty()) return
        clauses += "`$column` IN (${values.joinToString(", ") { "?" }})"
        values.forEach { args += text(it) }
    }

    fromMillis?.let {
        clauses += "`ts` >= ?"
        args += it
    }
    toMillis?.let {
        clauses += "`ts` <= ?"
        args += it
    }
    addSet("level", levels) { it.name }
    addSet("action", actions) { it }
    addSet("entity", entities) { it }
    addSet("entityId", entityIds) { it }
    addSet("actorId", actorIds) { it }
    addSet("outcome", outcomes) { it.name }
    addSet("correlationId", correlationIds) { it }

    return SqlWhere(clauses.joinToString(" AND "), args)
}

private fun rawQuery(
    select: String,
    filter: RoomLogFilter,
    extraClause: String? = null,
    orderBy: String? = null,
    limit: Int? = null,
    offset: Int = 0,
): RoomRawQuery {
    val where = filter.buildWhere()
    val clauses = buildList {
        if (where.clause.isNotEmpty()) add(where.clause)
        if (extraClause != null) add(extraClause)
    }
    val args = buildList {
        addAll(where.args)
        if (limit != null) add(limit.toLong())
        if (offset > 0) add(offset.toLong())
    }
    val sql = buildString(select.length + 96) {
        append(select)
        if (clauses.isNotEmpty()) append(" WHERE ").append(clauses.joinToString(" AND "))
        if (orderBy != null) append(" ORDER BY ").append(orderBy)
        if (limit != null) append(" LIMIT ?")
        if (offset > 0) append(" OFFSET ?")
    }
    return RoomRawQuery(sql) { statement ->
        args.forEachIndexed { index, arg ->
            when (arg) {
                is Long -> statement.bindLong(index + 1, arg)
                is String -> statement.bindText(index + 1, arg)
            }
        }
    }
}

private fun RoomLogPage.rawQuery(filter: RoomLogFilter): RoomRawQuery {
    val order = when (order) {
        RoomLogOrder.NEWEST_FIRST -> "`ts` DESC, `eventId` DESC"
        RoomLogOrder.OLDEST_FIRST -> "`ts` ASC, `eventId` ASC"
    }
    return rawQuery(
        select = "SELECT * FROM `${RoomLogSchema.TABLE}`",
        filter = filter,
        orderBy = order,
        limit = limit,
        offset = offset,
    )
}

private fun countRawQuery(filter: RoomLogFilter): RoomRawQuery = rawQuery(
    select = "SELECT COUNT(*) AS `value` FROM `${RoomLogSchema.TABLE}`",
    filter = filter,
)

private fun RoomLogFacet.column(): String = when (this) {
    RoomLogFacet.LEVEL -> "level"
    RoomLogFacet.ACTION -> "action"
    RoomLogFacet.ENTITY -> "entity"
    RoomLogFacet.ENTITY_ID -> "entityId"
    RoomLogFacet.ACTOR -> "actorId"
    RoomLogFacet.OUTCOME -> "outcome"
    RoomLogFacet.SOURCE -> "source"
    RoomLogFacet.CATEGORY -> "category"
    RoomLogFacet.CORRELATION_ID -> "correlationId"
}

private fun distinctRawQuery(facet: RoomLogFacet, filter: RoomLogFilter): RoomRawQuery {
    val column = "`${facet.column()}`"
    return rawQuery(
        select = "SELECT DISTINCT $column AS `value` FROM `${RoomLogSchema.TABLE}`",
        filter = filter,
        extraClause = "$column IS NOT NULL",
        orderBy = "`value` ASC",
    )
}
