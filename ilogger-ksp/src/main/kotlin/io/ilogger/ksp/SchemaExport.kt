package io.ilogger.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec

/**
 * One compile-time action contract: everything a consumer needs to route and
 * validate events of `(action, version)`. Several operations may emit the same
 * contract; divergent shapes for the same action+version are a build error.
 */
internal class ActionSchema(
    val action: String,
    val version: Int,
    val entity: String?,
    val metadata: List<String>,
    val changes: List<String>,
) {
    /** Every operation emitting this contract, sorted for determinism. */
    val sources = sortedSetOf<String>()

    /** `action` for v1, `action@vN` from v2 on (mirrors `LogEvent.schemaId`). */
    val schemaId: String get() = if (version <= 1) action else "$action@v$version"

    fun sameShape(other: ActionSchema): Boolean =
        entity == other.entity && metadata == other.metadata && changes == other.changes

    fun diffFrom(other: ActionSchema): String = buildList {
        if (entity != other.entity) add("entity '${other.entity}' vs '$entity'")
        if (metadata != other.metadata) add("metadata ${other.metadata} vs $metadata")
        if (changes != other.changes) add("changes ${other.changes} vs $changes")
    }.joinToString("; ")

    companion object {
        fun of(op: ResolvedOp): ActionSchema = ActionSchema(
            action = op.action,
            version = op.actionVersion,
            entity = op.entityName,
            metadata = op.metadata.map { it.name }.sorted(),
            changes = op.changes.map { it.field }.sorted(),
        )
    }
}

/**
 * Deterministic JSON manifest consumed by CI (schema diffs across releases)
 * and by tooling. Keys are emitted in a fixed order, actions sorted by
 * `(action, version)`, every array sorted.
 */
internal fun renderSchemaManifest(
    schemas: Collection<ActionSchema>,
    declared: Collection<String>,
): String {
    val actions = schemas.sortedWith(compareBy({ it.action }, { it.version }))
    val builder = StringBuilder()
    builder.append("{\n")
    builder.append("  \"formatVersion\": 1,\n")
    if (actions.isEmpty()) {
        builder.append("  \"actions\": [],\n")
    } else {
        builder.append("  \"actions\": [\n")
        actions.forEachIndexed { index, schema ->
            builder.append("    {\n")
            builder.append("      \"action\": ${jsonString(schema.action)},\n")
            builder.append("      \"version\": ${schema.version},\n")
            builder.append("      \"schemaId\": ${jsonString(schema.schemaId)},\n")
            builder.append("      \"entity\": ${schema.entity?.let(::jsonString) ?: "null"},\n")
            builder.append("      \"metadata\": ${jsonArray(schema.metadata)},\n")
            builder.append("      \"changes\": ${jsonArray(schema.changes)},\n")
            builder.append("      \"sources\": ${jsonArray(schema.sources.toList())}\n")
            builder.append("    }")
            if (index != actions.lastIndex) builder.append(',')
            builder.append('\n')
        }
        builder.append("  ],\n")
    }
    builder.append("  \"declared\": ${jsonArray(declared.sorted())}\n")
    builder.append("}\n")
    return builder.toString()
}

private fun jsonArray(values: List<String>): String =
    if (values.isEmpty()) "[]" else values.joinToString(", ", prefix = "[", postfix = "]") { jsonString(it) }

private fun jsonString(value: String): String = buildString {
    append('"')
    for (char in value) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}

/**
 * Generated companion of an `@LogActions` object: the declared codes as a
 * sorted immutable list, for tests and runtime introspection.
 */
internal fun buildActionRegistryFile(
    declaration: KSClassDeclaration,
    actions: List<String>,
): FileSpec {
    val name = declaration.simpleName.asString()
    val initializer = CodeBlock.builder()
        .add("listOf(")
        .apply {
            actions.forEachIndexed { index, action ->
                if (index > 0) add(", ")
                add("%S", action)
            }
        }
        .add(")")
        .build()
    val property = PropertySpec.builder("actions", LIST.parameterizedBy(STRING), KModifier.PUBLIC)
        .addKdoc("Every action code declared by [%L], sorted.\n", name)
        .initializer(initializer)
        .build()
    val registry = TypeSpec.objectBuilder("${name}_ILoggerActions")
        .addKdoc("Compiled view of the [%L] action registry.\n", name)
        .addProperty(property)
        .build()
    return FileSpec.builder(declaration.packageName.asString(), "${name}_ILoggerActions")
        .addFileComment("Generated by ilogger-ksp from @LogActions. DO NOT MODIFY.\n")
        .addType(registry)
        .build()
}
