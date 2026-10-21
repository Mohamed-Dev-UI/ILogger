package io.ilogger.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType

/**
 * Symbol-lookup helpers shared by the processor. Plain functions; no layered
 * abstraction. Room annotations are matched purely by qualified name so the
 * processor never requires a Room dependency at any scope.
 */
internal object Ks {

    const val LOG_FQN = "io.ilogger.annotations.Log"
    const val LOG_META_FQN = "io.ilogger.annotations.LogMeta"
    const val LOG_CHANGE_FQN = "io.ilogger.annotations.LogChange"
    const val LOG_SENSITIVE_FQN = "io.ilogger.annotations.LogSensitive"
    const val LOG_EXCLUDE_FQN = "io.ilogger.annotations.LogExclude"
    const val LOG_ENTITY_ID_FQN = "io.ilogger.annotations.LogEntityId"
    const val LOG_DAO_FQN = "io.ilogger.annotations.LogDao"

    const val NOTHING_FQN = "kotlin.Nothing"

    /**
     * KSP encodes annotation defaults of `Nothing::class` as `Void`
     * (`java.lang.Void`), so several qualified names represent "not supplied".
     */
    private val NOT_SUPPLIED_FQNS = setOf(NOTHING_FQN, "java.lang.Void", "kotlin.Void")

    fun isNotSuppliedType(type: KSType): Boolean =
        NOT_SUPPLIED_FQNS.contains(fqnOfType(type))

    // Room annotations, by qualified name only (optional integration).
    const val ROOM_PRIMARY_KEY = "androidx.room.PrimaryKey"
    const val ROOM_INSERT = "androidx.room.Insert"
    const val ROOM_UPSERT = "androidx.room.Upsert"
    const val ROOM_UPDATE = "androidx.room.Update"
    const val ROOM_DELETE = "androidx.room.Delete"
    const val ROOM_QUERY = "androidx.room.Query"
    const val ROOM_RAW_QUERY = "androidx.room.RawQuery"
    const val ROOM_TRANSACTION = "androidx.room.Transaction"
    const val ROOM_DB_INSPECTOR = "androidx.room.Database"
    const val ROOM_DAO = "androidx.room.Dao"

    /** Package prefixes that are never treated as domain entities. */
    private val nonDomainPrefixes = listOf(
        "kotlin.", "java.", "javax.", "android.", "androidx.", "kotlinx.", "io.ilogger.",
    )

    fun annotation(annotated: KSAnnotated, fqn: String): KSAnnotation? {
        for (candidate in annotated.annotations) {
            if (fqnOf(candidate) == fqn) return candidate
        }
        return null
    }

    fun fqnOf(annotation: KSAnnotation): String =
        annotation.annotationType.resolve().declaration.qualifiedName?.asString() ?: ""

    fun fqnOfType(type: KSType): String =
        type.declaration.qualifiedName?.asString() ?: ""

    /** Unwraps collection element types: `List<Task>`/`Iterable<Task>` → Task. */
    fun unwrapCollection(type: KSType): Pair<KSType, Boolean> {
        val fqn = type.declaration.qualifiedName?.asString() ?: return type to false
        val isCollection = when {
            fqn == "kotlin.Any" -> false
            fqn.startsWith("kotlin.collections.List") || fqn.startsWith("kotlin.collections.MutableList") ||
                fqn.startsWith("kotlin.collections.Collection") || fqn.startsWith("kotlin.collections.MutableCollection") ||
                fqn.startsWith("kotlin.collections.Iterable") ||
                fqn.startsWith("kotlin.collections.Set") || fqn.startsWith("kotlin.collections.MutableSet") -> true
            else -> false
        }
        if (!isCollection) return type to false
        val element = type.arguments.firstOrNull()?.type?.resolve()
        return if (element != null && !element.isError) element to true else type to false
    }

    fun packageName(type: KSType): String =
        type.declaration.qualifiedName?.asString()?.substringBeforeLast('.', "") ?: ""

    fun simpleName(type: KSType): String {
        val qualified = type.declaration.qualifiedName?.asString().orEmpty()
        return qualified.substringAfterLast('.', qualified).ifBlank { "Entity" }
    }

    /**
     * Returns the domain-entity candidate type when [type] is (or wraps) a
     * plausible application entity class. Returns null for framework, stdlib,
     * primitive and logger-internal types.
     */
    fun domainCandidate(type: KSType): KSType? {
        if (type.isError) return null
        val (unwrapped, _) = unwrapCollection(type)
        if (unwrapped.isError) return null
        val fqn = unwrapped.declaration.qualifiedName?.asString() ?: return null
        if (fqn.isBlank()) return null
        if (nonDomainPrefixes.any { fqn.startsWith(it) }) return null
        if (unwrapped.declaration != null && unwrapped.declaration.qualifiedName == null) return null
        return unwrapped
    }

    /** Loggable entity-id value types: stable primitives and strings. */
    fun isIdType(type: KSType): Boolean {
        if (type.isError) return false
        val fqn = type.declaration.qualifiedName?.asString() ?: return false
        return fqn in setOf("kotlin.Long", "kotlin.Int", "kotlin.Short", "kotlin.String")
    }

    /** Loggable metadata value types (primitives, strings, enums, one-level lists of those). */
    fun isLoggableValueType(type: KSType): Boolean {
        if (type.isError) return false
        // One unwrap: List<Task> checks Task. Nested lists reach a non-primitive
        // element type and are rejected (explicit extractors can handle them).
        val element = unwrapCollection(type).first
        if (element.isError) return false
        val fqn = element.declaration.qualifiedName?.asString() ?: return false
        if (fqn in setOf(
                "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
                "kotlin.Float", "kotlin.Double", "kotlin.Boolean", "kotlin.String",
            )
        ) return true
        val declaration = element.declaration
        return declaration is KSClassDeclaration && declaration.classKind == ClassKind.ENUM_CLASS
    }

    fun isUnit(type: KSType): Boolean =
        type.declaration.qualifiedName?.asString() == "kotlin.Unit"

    fun isFlow(type: KSType): Boolean =
        type.declaration.qualifiedName?.asString()?.startsWith("kotlinx.coroutines.flow.Flow") == true

    /**
     * Suspected-sensitive property names, by name alone — produces warnings
     * (never errors, to avoid false positives); explicit [LogExclude] /
     * [LogSensitive] annotations are the hard contract.
     */
    fun suspiciousName(name: String): Boolean {
        val lowered = name.lowercase()
        val fragments = listOf(
            "password", "passwd", "pwd", "secret", "credential", "apikey", "api_key",
            "refreshtoken", "accesstoken", "privatekey", "authorization", "cookie",
            "sessionkey", "clientsecret",
        )
        val normalized = lowered.replace("_", "")
        return fragments.any { normalized.contains(it) }
    }

    /**
     * Finds the identifier-bearing property of an entity type: explicit
     * [LogEntityId]/[LogExclude]-aware, then [Room PrimaryKey][ROOM_PRIMARY_KEY],
     * then a property named exactly `id`.
     */
    fun idPropertyOf(entity: KSClassDeclaration): KSPropertyDeclaration? {
        val properties = entity.getAllProperties()
        properties.firstOrNull { p ->
            annotation(p, LOG_ENTITY_ID_FQN) != null || annotation(p, ROOM_PRIMARY_KEY) != null
        }?.let { return it }
        return properties.firstOrNull { it.simpleName.asString() == "id" }
    }

    /** True when the property/class is expressly excluded from logging. */
    fun isExcluded(property: KSPropertyDeclaration): Boolean {
        if (annotation(property, LOG_EXCLUDE_FQN) != null) return true
        val owner = property.parentDeclaration as? KSClassDeclaration
        if (owner != null && annotation(owner, LOG_EXCLUDE_FQN) != null) return true
        return false
    }

    /** True when the property/class is marked sensitive (but not excluded). */
    fun isSensitive(property: KSPropertyDeclaration): Boolean {
        if (annotation(property, LOG_SENSITIVE_FQN) != null) return true
        val owner = property.parentDeclaration as? KSClassDeclaration
        if (owner != null && annotation(owner, LOG_SENSITIVE_FQN) != null) return true
        return false
    }
}
