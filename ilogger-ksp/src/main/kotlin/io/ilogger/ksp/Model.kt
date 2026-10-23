package io.ilogger.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType

/** How a selected chain value's sensitivity is treated (mirrors SensitiveHandling). */
internal enum class SensitiveMode { ID, CHANGE, META_DENY, META_OMIT, META_MASK, META_HASH }

/**
 * A compile-time-resolved source expression (see `@Log` KDoc for the grammar).
 * `chain` entries are raw property names; [ChainInfo] carries the resolved
 * declarations after [OpParser.validateChain] succeeds (set for every chain
 * expression that passed validation).
 */
internal sealed class ResolvedExpr {
    class Constant internal constructor(val text: String) : ResolvedExpr()
    class Param internal constructor(val index: Int, val param: String, val chain: List<String>) : ResolvedExpr() {
        /** Filled by [OpParser.validateChain]; non-null afterwards. */
        var chainInfo: ChainInfo? = null
    }

    class Result internal constructor(val chain: List<String>) : ResolvedExpr() {
        /** Filled by [OpParser.validateChain]; non-null afterwards. */
        var chainInfo: ChainInfo? = null
    }

    class ActorOf internal constructor(val access: String?) : ResolvedExpr()
    class ContextOf internal constructor(val key: String) : ResolvedExpr()

    /** Valid "no entity id" outcome (e.g. batch inserts, pure diagnostics). */
    object None : ResolvedExpr()

    val isChain: Boolean get() = this is Param || this is Result
}

/** Resolved property chain of a validated [ResolvedExpr.Param] / [ResolvedExpr.Result]. */
internal class ChainInfo(
    val properties: List<KSPropertyDeclaration>,
    /** True when the function returned `Result<T>` and the chain walks T (rendered via `getOrNull()`). */
    val resultUnwrapped: Boolean = false,
)

/** True when the expression reads the operation result (unavailable on START/FAILURE events). */
internal val ResolvedExpr.usesResult: Boolean get() = this is ResolvedExpr.Result

internal class ResolvedMeta(
    val name: String,
    val expr: ResolvedExpr,
    val sensitiveMode: SensitiveMode,
    val extractorFqn: String?, // null = no extractor
    val adapterFqn: String? = null, // null = no ValueAdapter
    /** `extractor` must come from the logger's ExtractorRegistry (no fallback). */
    val extractorInjected: Boolean = false,
    /** The extractor is an object; its fallback expression is the object name itself. */
    val extractorIsObject: Boolean = false,
)

internal class ResolvedChange(
    val field: String,
    val before: ResolvedExpr,
    val after: ResolvedExpr,
)

internal class ParameterModel(
    val name: String,
    val index: Int,
    val type: KSType,
    val vararg: Boolean,
    /** `@LogSensitive` on the parameter or its type. */
    val sensitive: Boolean = false,
    /** `@LogExclude` on the parameter or its type. */
    val excluded: Boolean = false,
)

/**
 * A fully validated `@Log` operation, ready for codegen.
 */
internal class ResolvedOp(
    val fn: KSFunctionDeclaration,
    /** Declaring interface/class, or null for top-level functions. */
    val containerDecl: KSClassDeclaration?,
    val containerPackage: String,
    /** Non-null for top-level extension functions; not addressable in source expressions. */
    val extensionReceiverType: KSType?,
    val suspendFn: Boolean,
    val unitReturn: Boolean,
    val returnType: KSType,
    val parameters: List<ParameterModel>,
    val action: String,
    val actionVersion: Int,
    val level: String,
    val category: String,
    val dispatch: String,
    val sampling: Double,
    val entityName: String?,
    val entityId: ResolvedExpr?,
    val actor: ResolvedExpr?,
    /** Policy applied to a sensitive actor override: `META_MASK`, `META_HASH` or `META_DENY` (plain). */
    val actorSensitiveMode: SensitiveMode = SensitiveMode.META_DENY,
    val metadata: List<ResolvedMeta>,
    val changes: List<ResolvedChange>,
    val before: Boolean,
    val onSuccess: Boolean,
    val onFailure: Boolean,
    val onCancellation: String,
    val description: String,
    val source: String,
    val usesContext: Boolean,
) {
    /** Container simple name (interface/class); null for top-level functions. */
    val containerName: String? get() = containerDecl?.simpleName?.asString()

    /** Whether any selected value needs the result; result is absent on START/FAILURE events. */
    val needsResult: Boolean
        get() = entityId?.let { it is ResolvedExpr.Result } == true ||
            actor?.let { it is ResolvedExpr.Result } == true ||
            metadata.any { it.expr is ResolvedExpr.Result } ||
            changes.any { it.before is ResolvedExpr.Result || it.after is ResolvedExpr.Result }

    /** Whether any selected value needs context/actor access (requires a local context val). */
    val needsContextLocal: Boolean
        get() = usesContext || metadata.any { it.expr is ResolvedExpr.ActorOf } ||
            entityId is ResolvedExpr.ActorOf || actor is ResolvedExpr.ActorOf
}
