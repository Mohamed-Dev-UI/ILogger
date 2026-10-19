package io.ilogger.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType

/** How a selected chain value's sensitivity is treated (mirrors SensitiveHandling). */
internal enum class SensitiveMode { ID, CHANGE, META_DENY, META_MASK, META_HASH }

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
internal class ChainInfo(val properties: List<KSPropertyDeclaration>)

/** True when the expression reads the operation result (unavailable on START/FAILURE events). */
internal val ResolvedExpr.usesResult: Boolean get() = this is ResolvedExpr.Result

internal class ResolvedMeta(
    val name: String,
    val expr: ResolvedExpr,
    val sensitiveMode: SensitiveMode,
    val extractorFqn: String?, // null = no extractor
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
)

/**
 * A fully validated `@Log` operation, ready for codegen.
 */
internal class ResolvedOp(
    val fn: KSFunctionDeclaration,
    val interfaceDecl: KSClassDeclaration,
    val interfacePackage: String,
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
    val metadata: List<ResolvedMeta>,
    val changes: List<ResolvedChange>,
    val before: Boolean,
    val onSuccess: Boolean,
    val onFailure: Boolean,
    val description: String,
    val source: String,
    val usesContext: Boolean,
) {
    /** Interface simple name; used for generated wrapper names. */
    val interfaceName: String get() = interfaceDecl.simpleName.asString()

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
