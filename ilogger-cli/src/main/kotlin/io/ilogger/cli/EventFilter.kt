package io.ilogger.cli

import io.ilogger.annotations.LogLevel
import io.ilogger.core.Actor
import io.ilogger.core.LogEvent
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Post-decode filter shared by `tail`, `export` and `replay`. Patterns match
 * exactly, or by prefix when they end with `*`; empty lists match everything.
 */
internal class EventFilter(
    private val levels: Set<LogLevel> = emptySet(),
    private val actions: List<String> = emptyList(),
    private val entities: List<String> = emptyList(),
    private val actors: List<String> = emptyList(),
    private val categories: List<String> = emptyList(),
    private val sources: List<String> = emptyList(),
    private val sinceMillis: Long? = null,
    private val untilMillis: Long? = null,
) {

    fun matches(event: LogEvent): Boolean {
        if (levels.isNotEmpty() && event.level !in levels) return false
        if (!matchesText(event.action, actions)) return false
        if (!matchesText(event.entity, entities)) return false
        if (!matchesActor(event.actor, actors)) return false
        if (!matchesText(event.category, categories)) return false
        if (!matchesText(event.source, sources)) return false
        if (sinceMillis != null && event.timestampMillis < sinceMillis) return false
        if (untilMillis != null && event.timestampMillis > untilMillis) return false
        return true
    }

    private fun matchesText(value: String?, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return true
        if (value == null) return false
        return patterns.any { pattern -> matchOne(value, pattern) }
    }

    private fun matchesActor(actor: Actor?, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return true
        if (actor == null) return false
        val candidates = buildList {
            actor.id?.let(::add)
            actor.name?.let(::add)
            actor.id?.let { add("${actor.type.name.lowercase()}:$it") }
        }
        return candidates.any { candidate -> patterns.any { pattern -> matchOne(candidate, pattern) } }
    }

    private fun matchOne(value: String, pattern: String): Boolean =
        if (pattern.endsWith("*")) value.startsWith(pattern.dropLast(1)) else value == pattern

    companion object {

        val NONE: EventFilter = EventFilter()

        fun from(options: Options, nowMillis: Long = System.currentTimeMillis()): EventFilter {
            val levels = options.list("level").map { raw ->
                LogLevel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    ?: throw UsageException(
                        "unknown level '$raw'; expected ${LogLevel.entries.joinToString(", ")}",
                    )
            }.toSet()

            val since = options.value("since")?.let { parseInstant(it, "--since") }
            val until = options.value("until")?.let { parseInstant(it, "--until") }
            val last = options.value("last")?.let { parseDuration(it) }
            if (last != null && (since != null || until != null)) {
                throw UsageException("--last cannot be combined with --since or --until")
            }
            if (since != null && until != null && since > until) {
                throw UsageException("--since must not be later than --until")
            }

            return EventFilter(
                levels = levels,
                actions = options.list("action"),
                entities = options.list("entity"),
                actors = options.list("actor"),
                categories = options.list("category"),
                sources = options.list("source"),
                sinceMillis = since ?: last?.let { nowMillis - it },
                untilMillis = until,
            )
        }

        private fun parseInstant(value: String, option: String): Long {
            value.toLongOrNull()?.let { return it }
            try {
                return Instant.parse(value).toEpochMilli()
            } catch (_: Exception) {
                // Try an offset timestamp, then a calendar date.
            }
            try {
                return OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: Exception) {
                // Try a local date next.
            }
            try {
                return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) {
                throw UsageException(
                    "$option '$value' is not a time; use epoch millis or ISO-8601 (2026-10-23 or 2026-10-23T10:15:30Z)",
                )
            }
        }

        private val DURATION = Regex("""^(\d+)([smhdw])$""")

        private fun parseDuration(value: String): Long {
            val match = DURATION.matchEntire(value.trim())
                ?: throw UsageException("--last '$value' is not a duration; use e.g. 30m, 12h, 7d")
            val amount = match.groupValues[1].toLongOrNull()
                ?: throw UsageException("--last '$value' is too large")
            if (amount <= 0) throw UsageException("--last '$value' must be greater than zero")
            val unit = when (match.groupValues[2]) {
                "s" -> 1_000L
                "m" -> 60_000L
                "h" -> 3_600_000L
                "d" -> 86_400_000L
                "w" -> 604_800_000L
                else -> throw UsageException("--last '$value' has an unknown unit")
            }
            return Math.multiplyExact(amount, unit)
        }
    }
}
