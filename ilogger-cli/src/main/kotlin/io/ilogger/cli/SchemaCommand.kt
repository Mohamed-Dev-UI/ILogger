package io.ilogger.cli

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Inspects the KSP-generated `META-INF/ilogger/schema.json` action registry. */
internal object SchemaCommand {

    private val VALUE_OPTIONS = setOf("manifest", "format", "action")
    private val PRETTY = Json { prettyPrint = true }

    fun execute(args: List<String>, out: PrintStream, err: PrintStream): Int {
        val options = Options.parse(args, VALUE_OPTIONS)
        if (options.has("help")) {
            out.println(help())
            return 0
        }
        val positional = options.positionals.firstOrNull()
        val manifest = options.value("manifest") ?: positional
            ?: throw UsageException("schema requires --manifest <schema.json>")
        if (options.value("manifest") != null && positional != null) {
            throw UsageException("pass the manifest once, as --manifest or as the positional argument")
        }
        val format = (options.value("format") ?: "table").lowercase()
        if (format !in setOf("table", "json")) throw UsageException("--format must be table or json")

        val path = Path.of(manifest)
        if (!Files.isRegularFile(path)) throw CliException("manifest not found: $path")
        val root = try {
            Json.parseToJsonElement(Files.readString(path))
        } catch (error: Exception) {
            throw CliException("invalid JSON in $path: ${error.message}", error)
        }
        val objectRoot = root as? JsonObject
            ?: throw CliException("invalid schema manifest: expected a JSON object")

        val version = objectRoot["formatVersion"]?.primitive()?.intOrNull
            ?: throw CliException("invalid schema manifest: missing or non-numeric formatVersion")
        if (version != 1) {
            throw CliException("unsupported schema manifest formatVersion $version (this CLI understands 1)")
        }
        val actionsElement = objectRoot["actions"] as? JsonArray
            ?: throw CliException("invalid schema manifest: missing actions array")
        val declared = (objectRoot["declared"] as? JsonArray)
            ?.mapIndexed { index, element ->
                element.primitive()?.contentOrNull
                    ?: throw CliException("invalid schema manifest: declared[$index] is not a string")
            }
            .orEmpty()

        val actions = actionsElement.mapIndexed { index, element -> parseAction(element, index) }
        val duplicate = actions.groupBy { it.schemaId }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            throw CliException("invalid schema manifest: duplicate schemaId '${duplicate.key}'")
        }

        val patterns = options.list("action")
        val filtered = if (patterns.isEmpty()) {
            actions
        } else {
            actions.filter { row -> patterns.any { pattern -> matches(row.action, pattern) } }
        }

        if (format == "json") {
            val payload = if (patterns.isEmpty()) {
                objectRoot
            } else {
                buildJsonObject {
                    put("formatVersion", version)
                    put("actions", JsonArray(filtered.map { it.raw }))
                    put("declared", JsonArray(declared.map { JsonPrimitive(it) }))
                }
            }
            out.println(PRETTY.encodeToString(JsonElement.serializer(), payload))
        } else {
            printTable(filtered, version, declared, out)
        }
        return 0
    }

    private fun printTable(
        actions: List<ActionRow>,
        version: Int,
        declared: List<String>,
        out: PrintStream,
    ) {
        val headers = listOf("ACTION", "VERSION", "ENTITY", "METADATA", "CHANGES", "SOURCES")
        val rows = actions.map { row ->
            listOf(
                row.action,
                "v${row.version}",
                row.entity ?: "-",
                row.metadata.joinToString(", ").ifEmpty { "-" },
                row.changes.joinToString(", ").ifEmpty { "-" },
                row.sources.size.toString(),
            )
        }
        val widths = headers.indices.map { column ->
            maxOf(headers[column].length, rows.maxOfOrNull { it[column].length } ?: 0)
        }

        fun render(cells: List<String>): String =
            cells.mapIndexed { index, cell -> cell.padEnd(widths[index]) }
                .joinToString("  ")
                .trimEnd()

        out.println(render(headers))
        out.println(widths.joinToString("  ") { "-".repeat(it) })
        rows.forEach { out.println(render(it)) }
        out.println()
        val declaredText = if (declared.isEmpty()) {
            "(none)"
        } else {
            declared.take(10).joinToString(", ") +
                if (declared.size > 10) ", … (+${declared.size - 10})" else ""
        }
        out.println(
            "formatVersion $version; ${actions.size} action contract(s); declared: $declaredText",
        )
    }

    private fun parseAction(element: JsonElement, index: Int): ActionRow {
        val raw = element as? JsonObject
            ?: throw CliException("invalid schema manifest: actions[$index] is not an object")
        val action = raw["action"]?.primitive()?.contentOrNull
            ?: throw CliException("invalid schema manifest: actions[$index].action is missing")
        val version = raw["version"]?.primitive()?.intOrNull
            ?: throw CliException("invalid schema manifest: actions[$index].version is missing")
        val schemaId = raw["schemaId"]?.primitive()?.contentOrNull
            ?: if (version <= 1) action else "$action@v$version"
        val entity = raw["entity"]?.primitive()?.contentOrNull
        return ActionRow(
            action = action,
            version = version,
            schemaId = schemaId,
            entity = entity,
            metadata = strings(raw, "metadata", index),
            changes = strings(raw, "changes", index),
            sources = strings(raw, "sources", index),
            raw = raw,
        )
    }

    private fun strings(raw: JsonObject, field: String, index: Int): List<String> {
        val array = raw[field] as? JsonArray ?: return emptyList()
        return array.mapIndexed { elementIndex, element ->
            element.primitive()?.contentOrNull
                ?: throw CliException(
                    "invalid schema manifest: actions[$index].$field[$elementIndex] is not a string",
                )
        }
    }

    private fun JsonElement?.primitive(): JsonPrimitive? = this as? JsonPrimitive

    private fun matches(action: String, pattern: String): Boolean =
        if (pattern.endsWith("*")) action.startsWith(pattern.dropLast(1)) else action == pattern

    private data class ActionRow(
        val action: String,
        val version: Int,
        val schemaId: String,
        val entity: String?,
        val metadata: List<String>,
        val changes: List<String>,
        val sources: List<String>,
        val raw: JsonObject,
    )

    fun help(): String = """
        Usage: ilogger schema --manifest <schema.json> [options]

        Reads the manifest emitted by the KSP processor (normally
        build/generated/ksp/main/resources/META-INF/ilogger/schema.json or the
        configured ilogger.schema-output file) and lists the action contracts.

        Options:
          --manifest PATH    Manifest file (alternative: first positional)
          --format table|json
          --action P[,P]     Only actions matching an exact value or prefix*

        Examples:
          ilogger schema --manifest build/ilogger/schema.json
          ilogger schema build/generated/ksp/main/resources/META-INF/ilogger/schema.json --format json
          ilogger schema --manifest schema.json --action 'task.*'
    """.trimIndent()
}
