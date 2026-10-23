package io.ilogger.cli

import io.ilogger.core.LogEvent
import io.ilogger.serialization.JsonEventCodec
import java.time.Instant

/** Stable text/CSV/JSON renderings shared by `tail`, `export` and `replay`. */
internal object EventFormat {

    val csvHeader: String = listOf(
        "timestamp",
        "level",
        "action",
        "actionVersion",
        "outcome",
        "entity",
        "entityId",
        "actorType",
        "actorId",
        "actorName",
        "source",
        "category",
        "correlationId",
        "causationId",
        "eventId",
        "failureType",
        "failureMessage",
        "metadata",
        "changes",
    ).joinToString(",")

    fun compact(event: LogEvent): String = buildString {
        append(Instant.ofEpochMilli(event.timestampMillis).toString())
        append(' ').append(event.level.name.padEnd(5))
        append(' ').append(event.schemaId)
        append(" entity=").append(event.entity ?: "-").append(':').append(event.entityId ?: "-")
        append(" actor=").append(actorLabel(event))
        append(" outcome=").append(event.outcome.name)
        event.correlationId?.let { append(" corr=").append(it) }
        event.causationId?.let { append(" cause=").append(it) }
        event.source?.let { append(" source=").append(it) }
        event.category?.let { append(" category=").append(it) }
        event.failure?.let { append(" failure=").append(it.errorType).append(": ").append(it.message ?: "") }
        if (event.metadata.isNotEmpty()) {
            append(" meta={")
            append(event.metadata.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" })
            append('}')
        }
        if (event.changes.isNotEmpty()) {
            append(" changes=[")
            append(event.changes.joinToString(", ") { "${it.field}:${it.before}->${it.after}" })
            append(']')
        }
    }

    fun jsonLine(event: LogEvent): String = JsonEventCodec.encode(event)

    fun csv(event: LogEvent): String {
        val encoded = JsonEventCodec.toJsonElement(event)
        return listOf(
            Instant.ofEpochMilli(event.timestampMillis).toString(),
            event.level.name,
            event.action,
            event.actionVersion.toString(),
            event.outcome.name,
            event.entity,
            event.entityId,
            event.actor?.type?.name,
            event.actor?.id,
            event.actor?.name,
            event.source,
            event.category,
            event.correlationId,
            event.causationId,
            event.id,
            event.failure?.errorType,
            event.failure?.message,
            encoded.get("metadata")?.toString(),
            encoded.get("changes")?.toString(),
        ).joinToString(",") { cell -> csvCell(cell) }
    }

    private fun actorLabel(event: LogEvent): String {
        val actor = event.actor ?: return "-"
        return "${actor.type.name.lowercase()}:${actor.id ?: actor.name ?: "-"}"
    }

    private fun csvCell(value: String?): String {
        if (value == null || value.isEmpty()) return ""
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            value.first().isWhitespace() || value.last().isWhitespace()
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
