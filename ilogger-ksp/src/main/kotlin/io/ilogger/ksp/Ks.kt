package io.ilogger.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

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
    const val LOG_CONFIG_FQN = "io.ilogger.annotations.LogConfig"
    const val NO_LOG_FQN = "io.ilogger.annotations.NoLog"
    const val LOG_ACTIONS_FQN = "io.ilogger.annotations.LogActions"

    /** Canonical action format: `<domain>.<event>[.<variant>]`. */
    val ACTION_REGEX = Regex("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)+$")

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
    fun domainCandidate(type: KSType, domainPackages: Set<String> = emptySet()): KSType? {
        if (type.isError) return null
        val (unwrapped, _) = unwrapCollection(type)
        if (unwrapped.isError) return null
        val declaration = unwrapped.declaration
        // Entities are concrete classes: type parameters (T, R) carry a
        // qualified name like `pkg.Owner.T` but name no loggable entity, and
        // value classes are values, not entities.
        if (declaration !is KSClassDeclaration) return null
        if (Modifier.VALUE in declaration.modifiers) return null
        val fqn = declaration.qualifiedName?.asString() ?: return null
        if (fqn.isBlank()) return null
        if (nonDomainPrefixes.any { fqn.startsWith(it) }) return null
        if (domainPackages.isNotEmpty() && domainPackages.none { fqn.startsWith(it) }) return null
        return unwrapped
    }

    /** Loggable entity-id value types: stable primitives and strings. */
    fun isIdType(type: KSType): Boolean {
        if (type.isError) return false
        val fqn = type.declaration.qualifiedName?.asString() ?: return false
        return fqn in setOf("kotlin.Long", "kotlin.Int", "kotlin.Short", "kotlin.String")
    }

    /** Scalar value types render deterministically without a converter. */
    private val scalarValueTypes = setOf(
        "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
        "kotlin.Float", "kotlin.Double", "kotlin.Boolean", "kotlin.String",
        "kotlin.Char",
        "kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong",
        "java.util.UUID", "java.time.Instant", "java.time.Duration",
        "java.math.BigDecimal", "java.math.BigInteger",
    )

    private val primitiveArrayTypes = setOf(
        "kotlin.IntArray", "kotlin.LongArray", "kotlin.ShortArray", "kotlin.ByteArray",
        "kotlin.DoubleArray", "kotlin.FloatArray", "kotlin.BooleanArray", "kotlin.CharArray",
        "kotlin.UIntArray", "kotlin.ULongArray", "kotlin.UShortArray", "kotlin.UByteArray",
    )

    /** Loggable metadata value types: scalars, enums, one-level lists/arrays/maps of those, value classes and Result. */
    fun isLoggableValueType(type: KSType): Boolean {
        if (type.isError) return false
        val declaration = type.declaration
        if (declaration is KSClassDeclaration && Modifier.VALUE in declaration.modifiers) {
            return isScalarValueType(type)
        }
        val fqn = declaration?.qualifiedName?.asString() ?: return false
        if (fqn in scalarValueTypes) return true
        // One unwrap for containers: List<Task> checks Task. Nested containers
        // reach a rejected element type (extractors/adapters can handle them).
        if (fqn == "kotlin.Result") {
            val element = type.arguments.firstOrNull()?.type?.resolve() ?: return false
            return !element.isError && isScalarValueType(element)
        }
        if (fqn.startsWith("kotlin.Array")) {
            val element = type.arguments.firstOrNull()?.type?.resolve() ?: return false
            return !element.isError && isScalarValueType(element)
        }
        if (fqn in primitiveArrayTypes) return true
        val isMap = fqn.startsWith("kotlin.collections.Map") || fqn.startsWith("kotlin.collections.MutableMap")
        if (isMap) {
            val key = type.arguments.getOrNull(0)?.type?.resolve() ?: return false
            val value = type.arguments.getOrNull(1)?.type?.resolve() ?: return false
            return !key.isError && !value.isError && isScalarValueType(key) && isScalarValueType(value)
        }
        val (element, wasCollection) = unwrapCollection(type)
        if (wasCollection) return !element.isError && isScalarValueType(element)
        return type.declaration is KSClassDeclaration &&
            (type.declaration as KSClassDeclaration).classKind == ClassKind.ENUM_CLASS
    }

    /** Non-container loggable values: scalars, enums, value classes over scalars. */
    private fun isScalarValueType(type: KSType): Boolean {
        if (type.isError) return false
        val declaration = type.declaration
        // A value class is loggable when its underlying type is: the wrapper
        // degrades to `toString()` at render time unless a ValueAdapter applies.
        if (declaration is KSClassDeclaration && Modifier.VALUE in declaration.modifiers) {
            val underlying = declaration.primaryConstructor?.parameters?.firstOrNull()?.type?.resolve()
                ?: return false
            return isScalarValueType(underlying)
        }
        val fqn = declaration?.qualifiedName?.asString() ?: return false
        if (fqn in scalarValueTypes) return true
        return declaration is KSClassDeclaration && declaration.classKind == ClassKind.ENUM_CLASS
    }

    fun isUnit(type: KSType): Boolean =
        type.declaration.qualifiedName?.asString() == "kotlin.Unit"

    fun isFlow(type: KSType): Boolean =
        type.declaration.qualifiedName?.asString()?.startsWith("kotlinx.coroutines.flow.Flow") == true

    /** Unwraps `Result<T>` → T. Returns (type, false) for everything else. */
    fun unwrapResult(type: KSType): Pair<KSType, Boolean> {
        val fqn = type.declaration.qualifiedName?.asString() ?: return type to false
        if (fqn != "kotlin.Result") return type to false
        val element = type.arguments.firstOrNull()?.type?.resolve() ?: return type to false
        return if (!element.isError) element to true else type to false
    }

    /** Unwraps `Flow<T>` → T. Returns (type, false) for everything else. */
    fun unwrapFlow(type: KSType): Pair<KSType, Boolean> {
        val fqn = type.declaration.qualifiedName?.asString() ?: return type to false
        if (!isFlow(type)) return type to false
        val element = type.arguments.firstOrNull()?.type?.resolve() ?: return type to false
        return if (!element.isError) element to true else type to false
    }

    /** Peels `Result<T>` and `Flow<T>` wrappers for entity inference (collections unwrap in [domainCandidate]). */
    fun unwrapStreamWrappers(type: KSType): KSType =
        unwrapFlow(unwrapResult(type).first).first

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

    /**
     * True when a value of [type] is sensitive as a whole: a type-use
     * annotation (`token: @LogSensitive String`) or a sensitive class.
     */
    fun isSensitiveType(type: KSType): Boolean {
        if (type.isError) return false
        if (type.annotations.any { fqnOf(it) == LOG_SENSITIVE_FQN }) return true
        val declaration = type.declaration
        return declaration is KSClassDeclaration && annotation(declaration, LOG_SENSITIVE_FQN) != null
    }

    /** True when a value of [type] is excluded as a whole (type-use or class). */
    fun isExcludedType(type: KSType): Boolean {
        if (type.isError) return false
        if (type.annotations.any { fqnOf(it) == LOG_EXCLUDE_FQN }) return true
        val declaration = type.declaration
        return declaration is KSClassDeclaration && annotation(declaration, LOG_EXCLUDE_FQN) != null
    }
}
