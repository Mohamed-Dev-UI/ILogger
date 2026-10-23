package io.ilogger.ksp

/**
 * Stable identifiers for every processor diagnostic. Codes are public
 * contract: they appear in build output, in [docs/23-diagnostics-catalog.md]
 * and in `@Suppress("ILGxxx")` annotations, so they are never reused or
 * renumbered.
 *
 * [summary] is the catalog row; [fix] is the generic remedy. Messages may add
 * a more specific `Fix:` line for the offending declaration.
 */
internal enum class Diagnostic(
    val code: String,
    val severity: Severity,
    val summary: String,
    val fix: String,
) {
    LOG_TARGET(
        "ILG001", Severity.ERROR,
        "@Log applied to a declaration that cannot be wrapped",
        "apply @Log to a function of an interface, an open/abstract class member, or a top-level function",
    ),
    FINAL_CLASS(
        "ILG002", Severity.ERROR,
        "@Log member of a final class",
        "mark the class open/abstract, move the operation into an interface, or call ILogger.record",
    ),
    CLASS_MEMBER_SHAPE(
        "ILG003", Severity.ERROR,
        "class wrapper cannot override the member (abstract, non-open, inner class or private constructor)",
        "instrument a concrete open member, make the class/constructor accessible, or move to an interface",
    ),
    DEFAULT_PACKAGE(
        "ILG004", Severity.ERROR,
        "generated wrapper would live in the default package",
        "move the declaration into a named package",
    ),
    ACTION_GRAMMAR(
        "ILG010", Severity.ERROR,
        "blank or malformed action code",
        "use '<domain>.<event>[.<variant>]', e.g. 'task.created'",
    ),
    SCHEMA_VERSION(
        "ILG011", Severity.ERROR,
        "invalid schemaVersion",
        "use schemaVersion >= 1",
    ),
    LOG_FIELD_VALUE(
        "ILG012", Severity.ERROR,
        "invalid @Log/@LogConfig field value",
        "use a member of the annotation's enum or a value in the documented range",
    ),
    AUDIT_SAMPLING(
        "ILG013", Severity.ERROR,
        "sampling on an AUDIT event",
        "remove sampling, use a diagnostic level, or configure audit sampling at the application level",
    ),
    FUNCTION_SHAPE(
        "ILG014", Severity.ERROR,
        "function signature cannot be instrumented as annotated",
        "make the function suspend for INLINE/LOG cancellation, declare explicit types, or remove the override",
    ),
    ENTITY_INFERENCE(
        "ILG015", Severity.ERROR,
        "cannot infer a single entity",
        "add @Log(entity = Task::class)",
    ),
    ENTITY_ID_INFERENCE(
        "ILG016", Severity.ERROR,
        "cannot infer a single entity id",
        "add @Log(entityId = \"<parameter or result path>\")",
    ),
    ENTITY_SOURCE(
        "ILG017", Severity.ERROR,
        "entity/entityId source is unusable",
        "reference a resolvable domain type and a parameter/result id, never the ambient actor",
    ),
    ACTOR_SOURCE(
        "ILG018", Severity.ERROR,
        "actor override references the ambient actor",
        "omit @Log(actor) to inherit the ambient actor, or select a parameter/result value",
    ),
    SENSITIVE_ACTOR(
        "ILG019", Severity.WARNING,
        "explicit actor override selects sensitive attributes",
        "verify the container policy is the intended projection, or select non-sensitive attributes",
    ),
    MISSING_AUDIT_ACTOR(
        "ILG020", Severity.WARNING,
        "audit operation has no explicit actor with ilogger.require-actor enabled",
        "add @Log(actor = ...), record an ambient actor, or remove ilogger.require-actor",
    ),
    UNBOUND_CATEGORY(
        "ILG021", Severity.WARNING,
        "category has no configured sink binding",
        "bind a sink to the category, or add it to ilogger.sink-categories",
    ),
    EXPRESSION_SYNTAX(
        "ILG030", Severity.ERROR,
        "invalid value-expression syntax or source",
        "use result, actor, context.<key>, a parameter name, a property chain or a quoted constant",
    ),
    PROPERTY_CHAIN(
        "ILG031", Severity.ERROR,
        "property chain is missing or excluded",
        "correct the chain, or relax @LogExclude for genuinely loggable data",
    ),
    SENSITIVE_POLICY(
        "ILG032", Severity.ERROR,
        "sensitive value selected without a policy",
        "choose MASK/HASH/OMIT or select a different value",
    ),
    SENSITIVE_ID(
        "ILG033", Severity.ERROR,
        "entity id selects a sensitive value",
        "pick a non-sensitive id source",
    ),
    SENSITIVE_CHANGE(
        "ILG034", Severity.ERROR,
        "change tracking selects a sensitive value",
        "track non-sensitive fields, or record the value through LogMeta with MASK/HASH",
    ),
    SUSPICIOUS_NAME(
        "ILG035", Severity.WARNING,
        "selected property name looks sensitive but carries no protection annotation",
        "confirm the selection, or mark the property @LogSensitive/@LogExclude",
    ),
    META_CONTRACT(
        "ILG040", Severity.ERROR,
        "invalid LogMeta declaration",
        "provide a value, a unique name and a supported loggable type, or an extractor/adapter",
    ),
    META_CONVERTER(
        "ILG041", Severity.ERROR,
        "invalid extractor/adapter declaration",
        "use one class that implements the converter interface and is constructible or registered",
    ),
    CHANGE_CONTRACT(
        "ILG042", Severity.ERROR,
        "invalid LogChange declaration",
        "provide a unique field name and loggable before/after values",
    ),
    LOG_DAO_TARGET(
        "ILG050", Severity.ERROR,
        "@LogDao applied to a non-interface",
        "apply it to the DAO interface (Room @Dao types are interfaces)",
    ),
    HILT_MISSING(
        "ILG051", Severity.ERROR,
        "@LogDao(hilt = true) without Hilt on the classpath",
        "add the Hilt dependencies, or remove hilt = true",
    ),
    KOIN_MISSING(
        "ILG052", Severity.ERROR,
        "@LogDao(koin = true) without Koin on the classpath",
        "add koin-core, or remove koin = true",
    ),
    UNUSED_LOG_DAO(
        "ILG053", Severity.WARNING,
        "@LogDao interface is not exposed by any @Database accessor",
        "expose it from a database, remove @LogDao, or set enabled = false",
    ),
    DUPLICATE_BINDING(
        "ILG054", Severity.ERROR,
        "the same DAO is bound by two databases",
        "keep the DI flag on one database, or bind the DAO manually with db.logged(logger)",
    ),
    LOG_ACTIONS_TARGET(
        "ILG060", Severity.ERROR,
        "@LogActions applied to a non-object",
        "declare `object TaskActions { ... }` and list its codes in @LogActions(...)",
    ),
    SINGLE_SEGMENT_ACTION(
        "ILG061", Severity.WARNING,
        "action code has a single segment",
        "use '<domain>.<event>', e.g. 'task.created'",
    ),
    DUPLICATE_ACTION(
        "ILG062", Severity.WARNING,
        "action code declared or emitted more than once",
        "give distinct operations distinct codes, or suppress ILG062 when the shared contract is intentional",
    ),
    UNDECLARED_ACTION(
        "ILG063", Severity.WARNING,
        "action used but absent from every @LogActions registry",
        "add the code to the registry object's @LogActions(...) arguments",
    ),
    SCHEMA_CONFLICT(
        "ILG064", Severity.ERROR,
        "same action+version with different payload shapes",
        "bump @Log(schemaVersion = ...) when the shape changes, or align the selections",
    ),
    SCHEMA_VERSION_NOISE(
        "ILG065", Severity.WARNING,
        "new schemaVersion with an identical shape",
        "keep a single schemaVersion; bump it only when the payload shape changes",
    ),
    ;

    enum class Severity { ERROR, WARNING }

    val isWarning: Boolean get() = severity == Severity.WARNING

    fun message(what: String): String = "[$code] $what"
}
