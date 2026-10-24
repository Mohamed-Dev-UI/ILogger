package io.ilogger.ksp

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/**
 * Where an `@Log`-annotated function lives. Drives validation and the codegen
 * shape: interfaces get delegation proxies, open/abstract classes get subclass
 * wrappers, top-level functions get adapter wrappers.
 */
internal sealed class Container {
    abstract val decl: KSClassDeclaration?

    class Interface(override val decl: KSClassDeclaration) : Container()
    class OpenClass(override val decl: KSClassDeclaration) : Container()
    object TopLevel : Container() {
        override val decl: KSClassDeclaration? get() = null
    }

    /** Unsupported container (object, enum, ...): parse reports the generic diagnostic. */
    object Rejected : Container() {
        override val decl: KSClassDeclaration? get() = null
    }
}

/**
 * Parses and validates one `@Log`-annotated function into [ResolvedOp].
 * Validation failures produce compile diagnostics; the function is skipped
 * ("never guess" — spec §48–49). Every diagnostic carries a stable
 * [Diagnostic] code so builds can be suppressed with `@Suppress("ILGxxx")`.
 */
internal class OpParser(
    private val report: (Diagnostic, String, Any) -> Unit,
    /** Package prefixes eligible for entity inference (`ilogger.domain-packages`); empty = blacklist-only. */
    private val domainPackages: Set<String> = emptySet(),
    /** Warn when an AUDIT operation has no explicit actor (`ilogger.require-actor`). */
    private val requireActor: Boolean = false,
    /** Categories with a sink binding (`ilogger.sink-categories`); empty disables the check. */
    private val sinkCategories: Set<String> = emptySet(),
    /** Module-wide default for `@Log(autoChanges = DEFAULT)` (`ilogger.auto-changes`). */
    private val autoChangesDefault: Boolean = false,
) {

    private fun error(code: Diagnostic, message: String, symbol: Any) = report(code, message, symbol)

    private fun warn(code: Diagnostic, message: String, symbol: Any) = report(code, message, symbol)

    fun parse(
        fn: KSFunctionDeclaration,
        annotation: KSAnnotation,
        roomKind: String?,
        container: Container,
    ): ResolvedOp? {
        // ---- enclosing declaration -------------------------------------------
        if (container is Container.Rejected) {
            error(
                Diagnostic.LOG_TARGET,
                "@Log must be applied to a function of an interface, a member of an open or abstract class, " +
                    "or a top-level function. ILogger generates a delegating implementation for interfaces and " +
                    "open/abstract classes, and an adapter wrapper for top-level functions.\n" +
                    "Fix: move '${fn.simpleName.asString()}' into an interface, an open/abstract class, or the top level.",
                fn,
            )
            return null
        }
        val containerDecl = container.decl
        val containerPackage = containerDecl?.packageName?.asString() ?: fn.packageName.asString()
        when (container) {
            is Container.OpenClass -> {
                if (Modifier.ABSTRACT in fn.modifiers) {
                    error(
                        Diagnostic.CLASS_MEMBER_SHAPE,
                        "@Log cannot instrument the abstract member '${fn.simpleName.asString()}': the generated " +
                            "subclass wrapper has no implementation to delegate to.\n" +
                            "Fix: annotate the concrete open member that implements it, or move the operation " +
                            "into an interface.",
                        fn,
                    )
                    return null
                }
                if (Modifier.OPEN !in fn.modifiers) {
                    error(
                        Diagnostic.CLASS_MEMBER_SHAPE,
                        "@Log on class member '${fn.simpleName.asString()}' requires an open member: the subclass " +
                            "wrapper overrides open members only.\n" +
                            "Fix: mark the member open, or move the operation into an interface.",
                        fn,
                    )
                    return null
                }
            }
            else -> Unit
        }
        if (containerPackage.isBlank()) {
            val subject = if (containerDecl == null) "Top-level functions" else
                if (container is Container.Interface) "Interfaces" else "Classes"
            error(
                Diagnostic.DEFAULT_PACKAGE,
                "$subject in the default package cannot receive generated wrappers.\n" +
                    "Fix: move into a named package.",
                fn,
            )
            return null
        }

        // ---- scalar annotation fields ------------------------------------------
        val action = (annotation.arg("action") as? String) ?: ""
        if (action.isBlank()) {
            error(
                Diagnostic.ACTION_GRAMMAR,
                "@Log requires a non-blank action.\nFix: @Log(\"task.created\") or @Log(TaskActions.CREATED).",
                fn,
            )
            return null
        }
        if (!Ks.ACTION_REGEX.matches(action)) {
            error(
                Diagnostic.ACTION_GRAMMAR,
                "Invalid action '$action'. Actions must be dotted canonical codes such as 'task.created'.\n" +
                    "Fix: use '<domain>.<event>[.<variant>]' with no spaces, quotes or special characters.",
                fn,
            )
            return null
        }
        val actionVersion = (annotation.arg("schemaVersion") as? Int) ?: 1
        if (actionVersion < 1) {
            error(
                Diagnostic.SCHEMA_VERSION,
                "@Log(schemaVersion = $actionVersion) is invalid: schemaVersion must be >= 1.\n" +
                    "Fix: use schemaVersion = 1 for the first shape and increment only when it changes.",
                fn,
            )
            return null
        }
        // Class-level defaults (@LogConfig) fill in fields left at their @Log
        // defaults; explicit method values always win. AUDIT is both the
        // default and a meaningful value, so a method that must stay AUDIT
        // under a non-AUDIT config sets useClassDefaults = false.
        val classConfig = containerDecl?.let { Ks.annotation(it, Ks.LOG_CONFIG_FQN) }
        val classLevel = classConfig?.arg("level")?.let { enumConstantName(it) } ?: "AUDIT"
        if (classLevel !in LOG_LEVELS) {
            error(
                Diagnostic.LOG_FIELD_VALUE,
                "@LogConfig(level = ...) is invalid: '$classLevel' is not a member of " +
                    "io.ilogger.annotations.LogLevel.\nFix: use TRACE, DEBUG, INFO, WARN, ERROR or AUDIT.",
                containerDecl ?: fn,
            )
            return null
        }
        val classCategory = (classConfig?.arg("category") as? String).orEmpty().trim()
        val classDispatch = classConfig?.arg("dispatch")?.let { enumConstantName(it) } ?: "DEFAULT"
        if (classDispatch !in DISPATCH_MODES) {
            error(
                Diagnostic.LOG_FIELD_VALUE,
                "@LogConfig(dispatch = ...) is invalid: '$classDispatch' is not a member of " +
                    "io.ilogger.annotations.DispatchMode.\nFix: use DEFAULT, INLINE, ASYNC or BUFFERED.",
                containerDecl ?: fn,
            )
            return null
        }
        val classSampling = (classConfig?.arg("sampling") as? Double) ?: -1.0
        if (classSampling != -1.0 && classSampling !in 0.0..1.0) {
            error(
                Diagnostic.LOG_FIELD_VALUE,
                "@LogConfig(sampling = $classSampling) is invalid: use -1.0 (inherit) or a rate within " +
                    "[0.0..1.0].\nFix: pick a rate such as 0.5, or drop the override.",
                containerDecl ?: fn,
            )
            return null
        }
        val classSensitive = classConfig?.arg("sensitive")?.let { enumConstantName(it) } ?: "DENY"
        if (classSensitive !in SENSITIVE_MODES) {
            error(
                Diagnostic.LOG_FIELD_VALUE,
                "@LogConfig(sensitive = $classSensitive) is invalid: use DENY, OMIT, MASK or HASH.\n" +
                    "Fix: pick one of the SensitiveHandling constants.",
                containerDecl ?: fn,
            )
            return null
        }
        val useClassDefaults = (annotation.arg("useClassDefaults") as? Boolean) ?: true

        val methodLevel = annotation.arg("level")?.let { enumConstantName(it) } ?: "AUDIT"
        if (methodLevel !in LOG_LEVELS) {
            error(
                Diagnostic.LOG_FIELD_VALUE,
                "@Log(level = ...) is invalid: '$methodLevel' is not a member of io.ilogger.annotations.LogLevel.\n" +
                    "Fix: use TRACE, DEBUG, INFO, WARN, ERROR or AUDIT.",
                fn,
            )
            return null
        }
        val methodCategory = (annotation.arg("category") as? String).orEmpty().trim()
        val methodDispatch = annotation.arg("dispatch")?.let { enumConstantName(it) } ?: "DEFAULT"
        if (methodDispatch !in DISPATCH_MODES) {
            error(
                Diagnostic.LOG_FIELD_VALUE,
                "@Log(dispatch = ...) is invalid: '$methodDispatch' is not a member of " +
                    "io.ilogger.annotations.DispatchMode.\nFix: use DEFAULT, INLINE, ASYNC or BUFFERED.",
                fn,
            )
            return null
        }
        val before = (annotation.arg("before") as? Boolean) ?: false
        val onSuccess = (annotation.arg("onSuccess") as? Boolean) ?: true
        val onFailure = (annotation.arg("onFailure") as? Boolean) ?: false
        val onCancellation =
            annotation.arg("onCancellation")?.let { enumConstantName(it) } ?: "SKIP"
        if (onCancellation != "SKIP" && onCancellation != "LOG") {
            error(
                Diagnostic.LOG_FIELD_VALUE,
                "@Log(onCancellation = $onCancellation) is invalid: use OnCancellation.SKIP or " +
                    "OnCancellation.LOG.\nFix: remove the override (SKIP is the default) or use LOG.",
                fn,
            )
            return null
        }
        if (!before && !onSuccess && !onFailure && onCancellation != "LOG") {
            error(
                Diagnostic.LOG_FIELD_VALUE,
                "@Log would never emit an event (before=false, onSuccess=false, onFailure=false, " +
                    "onCancellation=SKIP).\n" +
                    "Fix: enable at least one of before/onSuccess/onFailure, or set onCancellation = LOG.",
                fn,
            )
            return null
        }
        val methodSampling = (annotation.arg("sampling") as? Double) ?: -1.0
        if (methodSampling != -1.0 && methodSampling !in 0.0..1.0) {
            error(
                Diagnostic.LOG_FIELD_VALUE,
                "@Log(sampling = $methodSampling) is invalid: use -1.0 (inherit) or a rate within [0.0..1.0].\n" +
                    "Fix: remove the override or use a rate in [0.0..1.0].",
                fn,
            )
            return null
        }
        val level =
            if (useClassDefaults && methodLevel == "AUDIT" && classLevel != "AUDIT") classLevel else methodLevel
        val category =
            if (useClassDefaults && methodCategory.isEmpty() && classCategory.isNotEmpty()) classCategory
            else methodCategory
        val dispatch =
            if (useClassDefaults && methodDispatch == "DEFAULT" && classDispatch != "DEFAULT") classDispatch
            else methodDispatch
        val sampling =
            if (useClassDefaults && methodSampling == -1.0 && classSampling != -1.0) classSampling
            else methodSampling
        if (sampling != -1.0 && sampling < 1.0 && level == "AUDIT") {
            error(
                Diagnostic.AUDIT_SAMPLING,
                "Audit events must not be sampled: @Log(level = AUDIT, sampling = $sampling) would silently lose " +
                    "accountability records.\nFix: remove sampling, use a diagnostic level, or configure explicit " +
                    "audit sampling at the application level.",
                fn,
            )
            return null
        }
        val description = (annotation.arg("description") as? String).orEmpty()

        // ---- function shape ------------------------------------------------------
        val suspendFn = fn.modifiers.any { it == Modifier.SUSPEND }
        val extensionReceiverType = fn.extensionReceiver?.resolve()
        if (extensionReceiverType != null && extensionReceiverType.isError) {
            error(
                Diagnostic.FUNCTION_SHAPE,
                "Extension receiver has an unresolved type; @Log cannot instrument it.\n" +
                    "Fix: declare or import the receiver type explicitly.",
                fn,
            )
            return null
        }
        if (onCancellation == "LOG" && !suspendFn) {
            error(
                Diagnostic.FUNCTION_SHAPE,
                "@Log(onCancellation = LOG) requires a suspend function or a Flow return: only suspend calls " +
                    "and flow collections can be cancelled.\nFix: make the function suspend or Flow-returning, " +
                    "or remove the onCancellation override.",
                fn,
            )
            return null
        }
        if (dispatch == "INLINE" && !suspendFn) {
            error(
                Diagnostic.FUNCTION_SHAPE,
                "@Log(dispatch = INLINE) cannot instrument non-suspend, non-flow functions: INLINE delivery " +
                    "requires the suspend API record().\nFix: make the function suspend or Flow-returning, " +
                    "or remove the dispatch override.",
                fn,
            )
            return null
        }
        val parameters = ArrayList<ParameterModel>(fn.parameters.size)
        for ((index, parameter) in fn.parameters.withIndex()) {
            val name = parameter.name?.asString()
            if (name.isNullOrBlank()) {
                error(
                    Diagnostic.FUNCTION_SHAPE,
                    "Parameter #$index has no name (unsupported form) in function " +
                        "'${fn.simpleName.asString()}'.\n" +
                        "Fix: declare a named parameter; Kotlin requires names on interface members.",
                    fn,
                )
                return null
            }
            val type = parameter.type.resolve()
            if (type.isError) {
                error(
                    Diagnostic.FUNCTION_SHAPE,
                    "Parameter '$name' has an unresolved type; @Log cannot instrument it.\n" +
                        "Fix: declare or import the parameter type explicitly.",
                    fn,
                )
                return null
            }
            parameters.add(
                ParameterModel(
                    name = name,
                    index = index,
                    type = type,
                    vararg = parameter.isVararg,
                    sensitive = Ks.annotation(parameter, Ks.LOG_SENSITIVE_FQN) != null ||
                        Ks.isSensitiveType(type),
                    excluded = Ks.annotation(parameter, Ks.LOG_EXCLUDE_FQN) != null ||
                        Ks.isExcludedType(type),
                ),
            )
        }
        val returnType = fn.returnType?.resolve()
        if (returnType == null || returnType.isError) {
            error(
                Diagnostic.FUNCTION_SHAPE,
                "Function '${fn.simpleName.asString()}' lacks an explicit, resolvable return type.\n" +
                    "Fix: declare the return type explicitly (Unit counts).",
                fn,
            )
            return null
        }
        val unitReturn = Ks.isUnit(returnType)
        val flowReturn = Ks.isFlow(returnType)
        if (onCancellation == "LOG" && !suspendFn && !flowReturn) {
            error(
                Diagnostic.FUNCTION_SHAPE,
                "@Log(onCancellation = LOG) requires a suspend function or a Flow return: only suspend calls " +
                    "and flow collections can be cancelled.\nFix: make the function suspend or Flow-returning, " +
                    "or remove the onCancellation override.",
                fn,
            )
            return null
        }
        if (dispatch == "INLINE" && !suspendFn && !flowReturn) {
            error(
                Diagnostic.FUNCTION_SHAPE,
                "@Log(dispatch = INLINE) cannot instrument non-suspend, non-flow functions: INLINE delivery " +
                    "requires the suspend API record().\nFix: make the function suspend or Flow-returning, " +
                    "or remove the dispatch override.",
                fn,
            )
            return null
        }
        // Parameters named result/actor/context are accepted: source
        // expressions resolve special heads first, and codegen names its
        // locals so user parameter names cannot collide (G-19).

        // ---- auto changes decision -------------------------------------------------
        // Resolved before entity-id inference: for the two-parameter shape the
        // "after" object is the current entity and supplies the inferred id.
        val explicitAutoChanges = annotation.arg("autoChanges")?.let { enumConstantName(it) }
        if (explicitAutoChanges != null && explicitAutoChanges !in AUTO_CHANGES_MODES) {
            error(
                Diagnostic.LOG_FIELD_VALUE,
                "@Log(autoChanges = $explicitAutoChanges) is invalid: use AutoChanges.DEFAULT, OFF or ALL.\n" +
                    "Fix: remove the override (DEFAULT inherits the module setting) or pick OFF/ALL.",
                fn,
            )
            return null
        }
        val autoRequired = explicitAutoChanges == "ALL"
        val autoEnabled = when (explicitAutoChanges) {
            "ALL" -> true
            "OFF" -> false
            else -> autoChangesDefault
        }
        val autoPair = if (autoEnabled) inferAutoPair(parameters, returnType, flowReturn) else null

        // ---- entity ----------------------------------------------------------------
        val explicitEntityArg = annotation.arg("entity") as? KSType
        val explicitEntity = explicitEntityArg?.takeIf { !Ks.isNotSuppliedType(it) }
        var entityName: String? = null
        if (explicitEntity != null) {
            if (explicitEntity.isError) {
                error(
                    Diagnostic.ENTITY_SOURCE,
                    "@Log(entity = ...) references an unresolvable type.\n" +
                        "Fix: import the entity type and reference it as Task::class.",
                    fn,
                )
                return null
            }
            entityName = Ks.simpleName(explicitEntity)
        }
        if (entityName == null) {
            val candidates = sortedSetOf<String>()
            parameters.mapNotNull { Ks.domainCandidate(it.type, domainPackages) }.forEach { candidates.add(Ks.simpleName(it)) }
            // Result/Flow-wrapped returns still attribute their element type.
            if (!unitReturn) {
                Ks.domainCandidate(Ks.unwrapStreamWrappers(returnType), domainPackages)?.let { candidates.add(Ks.simpleName(it)) }
            }
            entityName = when (candidates.size) {
                1 -> candidates.first()
                0 -> null
                else -> {
                    error(
                        Diagnostic.ENTITY_INFERENCE,
                        "Cannot infer entity: multiple candidate types $candidates. ILogger will not guess.\n" +
                            "Fix: specify @Log(entity = Task::class) explicitly.",
                        fn,
                    )
                    return null
                }
            }
        }

        // ---- entityId -----------------------------------------------------------------
        val explicitEntityId = (annotation.arg("entityId") as? String).orEmpty()
        val entityId: ResolvedExpr? = if (explicitEntityId.isNotBlank()) {
            val parsed = resolveExpr(explicitEntityId, parameters, returnType, "@Log(entityId)", fn, allowResult = true)
                ?: return null
            when (parsed) {
                is ResolvedExpr.Param, is ResolvedExpr.Result ->
                    if (validateChain(parsed, parameters, returnType, SensitiveMode.ID, fn) == null) return null
                is ResolvedExpr.Constant, is ResolvedExpr.ContextOf -> Unit
                is ResolvedExpr.ActorOf, is ResolvedExpr.None -> {
                    error(
                        Diagnostic.ENTITY_SOURCE,
                        "@Log(entityId = ...) cannot reference the ambient actor.\n" +
                            "Fix: select a parameter, result or constant; actor identity is recorded separately.",
                        fn,
                    )
                    return null
                }
            }
            parsed
        } else {
            // The auto-captured "after" object is the current entity, so its id
            // is the event's entity id (a generated id on insert included).
            val inferred = autoEntityId(autoPair)
                ?: inferEntityId(parameters, returnType, fn, roomKind)
                ?: return null
            if (inferred.isChain) {
                validateChain(inferred, parameters, returnType, SensitiveMode.ID, fn) ?: return null
            }
            inferred
        }

        // ---- actor override --------------------------------------------------------------
        val explicitActor = (annotation.arg("actor") as? String).orEmpty()
        var actor: ResolvedExpr? = null
        var actorSensitiveMode = SensitiveMode.META_DENY
        if (explicitActor.isNotBlank()) {
            val parsed = resolveExpr(explicitActor, parameters, returnType, "@Log(actor)", fn, allowResult = false)
                ?: return null
            when (parsed) {
                is ResolvedExpr.ActorOf, is ResolvedExpr.None -> {
                    error(
                        Diagnostic.ACTOR_SOURCE,
                        "@Log(actor) cannot reference the ambient actor — omit @Log(actor) to use it.\n" +
                            "Fix: select a parameter/result value, or remove the override.",
                        fn,
                    )
                    return null
                }
                is ResolvedExpr.Param, is ResolvedExpr.Result -> {
                    // Actor attributes follow the container sensitive policy:
                    // MASK/HASH sanitize, OMIT drops the actor, DENY refuses.
                    val fallback = when (classSensitive) {
                        "MASK" -> SensitiveMode.META_MASK
                        "HASH" -> SensitiveMode.META_HASH
                        "OMIT" -> SensitiveMode.META_OMIT
                        else -> null
                    }
                    val blaze =
                        validateChain(parsed, parameters, returnType, SensitiveMode.META_DENY, fn, fallback)
                            ?: return null
                    if (!blaze.omitted) {
                        if (blaze.sensitiveTouched) {
                            actorSensitiveMode = when (classSensitive) {
                                "MASK" -> SensitiveMode.META_MASK
                                "HASH" -> SensitiveMode.META_HASH
                                else -> SensitiveMode.META_DENY
                            }
                            warn(
                                Diagnostic.SENSITIVE_ACTOR,
                                "Actor override '$explicitActor' selects sensitive values; the event records the " +
                                    "$classSensitive projection instead of the raw value.\n" +
                                    "Fix: keep the container policy if that projection is intended, or select " +
                                    "non-sensitive actor attributes.",
                                fn,
                            )
                        }
                        actor = parsed
                    } else if (level == "AUDIT") {
                        warn(
                            Diagnostic.SENSITIVE_ACTOR,
                            "Actor override '$explicitActor' selects sensitive values and the container OMIT policy " +
                                "drops it; this AUDIT event falls back to the ambient actor.\n" +
                                "Fix: record a non-sensitive actor attribute, or use MASK/HASH for attribution.",
                            fn,
                        )
                    }
                }
                else -> actor = parsed
            }
        }

        if (requireActor && level == "AUDIT" && explicitActor.isBlank()) {
            warn(
                Diagnostic.MISSING_AUDIT_ACTOR,
                "Audit operation '${containerDecl?.simpleName?.asString()?.plus(".") ?: ""}" +
                    "${fn.simpleName.asString()}' has no explicit actor and depends on the ambient " +
                    "actor or ILoggerConfig.actorProvider.\n" +
                    "Fix: add @Log(actor = ...) for deterministic attribution, wrap the call in withActor { }, " +
                    "or remove ilogger.require-actor.",
                fn,
            )
        }

        if (sinkCategories.isNotEmpty() && category.isNotEmpty() && category !in sinkCategories) {
            warn(
                Diagnostic.UNBOUND_CATEGORY,
                "Category '$category' used by '${containerDecl?.simpleName?.asString()?.plus(".") ?: ""}" +
                    "${fn.simpleName.asString()}' is not in the configured sink categories " +
                    "$sinkCategories, so no sink may receive it.\n" +
                    "Fix: bind a sink to '$category', or add it to ilogger.sink-categories / " +
                    "ilogger { sinkCategories.add(\"$category\") } if the binding happens elsewhere.",
                fn,
            )
        }

        // ---- metadata -----------------------------------------------------------------------
        val metadataAnnotations = annotationEntries(annotation.arg("metadata"), Ks.LOG_META_FQN)
        val metadata = ArrayList<ResolvedMeta>(metadataAnnotations.size)
        val metaNames = HashSet<String>()
        for (entry in metadataAnnotations) {
            val valueExpr = (entry.arg("value") as? String).orEmpty()
            if (valueExpr.isBlank()) {
                error(
                    Diagnostic.META_CONTRACT,
                    "LogMeta requires a value expression.\nFix: LogMeta(\"task.priority\") etc.",
                    fn,
                )
                return null
            }
            val parsed = resolveExpr(valueExpr, parameters, returnType, "LogMeta", fn, allowResult = true) ?: return null

            val sensitiveName = entry.arg("sensitive")?.let { enumConstantName(it) } ?: "DENY"
            if (sensitiveName !in SENSITIVE_MODES) {
                error(
                    Diagnostic.META_CONTRACT,
                    "Invalid LogMeta sensitive = $sensitiveName.\nFix: use DENY, OMIT, MASK or HASH.",
                    fn,
                )
                return null
            }
            val inject = entry.arg("inject") as? Boolean ?: false
            if (inject && !hasExtractorRef(entry)) {
                error(
                    Diagnostic.META_CONTRACT,
                    "LogMeta('$valueExpr', inject = true) requires an extractor.\n" +
                        "Fix: add extractor = MyExtractor::class, or drop inject.",
                    fn,
                )
                return null
            }
            val extractor = if (hasExtractorRef(entry)) validateExtractor(entry, inject) ?: return null else null
            val extractorFqn = extractor?.fqn
            val adapterFqn = if (hasAdapterRef(entry)) validateAdapter(entry) ?: return null else null
            if (extractorFqn != null && adapterFqn != null) {
                error(
                    Diagnostic.META_CONVERTER,
                    "LogMeta('$valueExpr') combines an extractor with an adapter.\n" +
                        "Fix: keep one converter — extractor (LogValueExtractor) for full control over the " +
                        "log-safe shape, adapter (ValueAdapter) for a single-value render.",
                    fn,
                )
                return null
            }
            if (extractorFqn != null && sensitiveName in setOf("MASK", "HASH")) {
                error(
                    Diagnostic.META_CONVERTER,
                    "LogMeta('$valueExpr') combines an explicit extractor with sensitive = $sensitiveName.\n" +
                        "Fix: keep the extractor (it already defines the log-safe shape), or drop it and use " +
                        "sensitive = $sensitiveName alone.",
                    fn,
                )
                return null
            }
            if (adapterFqn != null && sensitiveName in setOf("MASK", "HASH")) {
                error(
                    Diagnostic.META_CONVERTER,
                    "LogMeta('$valueExpr') combines an adapter with sensitive = $sensitiveName.\n" +
                        "Fix: keep the adapter (it already defines the log-safe shape), or drop it and use " +
                        "sensitive = $sensitiveName alone.",
                    fn,
                )
                return null
            }

            val mode = when (sensitiveName) {
                "MASK" -> SensitiveMode.META_MASK
                "HASH" -> SensitiveMode.META_HASH
                "OMIT" -> SensitiveMode.META_OMIT
                else -> SensitiveMode.META_DENY
            }
            var resolvedMode = mode
            if (parsed.isChain) {
                if (mode == SensitiveMode.META_OMIT && (extractorFqn != null || adapterFqn != null)) {
                    error(
                        Diagnostic.META_CONTRACT,
                        "LogMeta('$valueExpr', sensitive = OMIT) cannot combine with an extractor/adapter: the " +
                            "entry is omitted, so the converter would never run.\n" +
                            "Fix: remove the converter, or keep it and choose MASK/HASH instead.",
                        fn,
                    )
                    return null
                }
                // A converter defines the log-safe shape itself, so the class
                // sensitive policy never stacks on top of one.
                val sensitiveFallback =
                    if (mode == SensitiveMode.META_DENY && extractorFqn == null && adapterFqn == null &&
                        classSensitive in setOf("MASK", "HASH", "OMIT")
                    ) {
                        when (classSensitive) {
                            "MASK" -> SensitiveMode.META_MASK
                            "HASH" -> SensitiveMode.META_HASH
                            else -> SensitiveMode.META_OMIT
                        }
                    } else {
                        null
                    }
                val blaze =
                    validateChain(parsed, parameters, returnType, mode, fn, sensitiveFallback) ?: return null
                // OMIT emits no key at all: no name, no converter call, no
                // allocation. An explicit OMIT always drops the entry; the
                // container-wide policy drops only sensitive-touched entries.
                if (mode == SensitiveMode.META_OMIT || blaze.omitted) continue
                if (sensitiveFallback != null && blaze.sensitiveTouched) resolvedMode = sensitiveFallback
                if (!Ks.isLoggableValueType(blaze.terminal) && extractorFqn == null && adapterFqn == null) {
                    error(
                        Diagnostic.META_CONTRACT,
                        "Unsupported metadata type '${Ks.simpleName(blaze.terminal)}' for LogMeta('$valueExpr').\n" +
                            "Fix: select loggable properties (numbers, booleans, strings, chars, enums, UUID, " +
                            "Instant, Duration, BigDecimal, one-level lists/arrays/maps of those, value classes), " +
                            "or provide LogMeta(extractor = ... / adapter = ...) to convert explicitly.",
                        fn,
                    )
                    return null
                }
            } else if (mode == SensitiveMode.META_OMIT) {
                error(
                    Diagnostic.META_CONTRACT,
                    "LogMeta('$valueExpr', sensitive = OMIT) cannot apply to a constant, actor or context " +
                        "source — there is no marked value to omit.\nFix: remove the entry instead.",
                    fn,
                )
                return null
            }

            val explicitName = (entry.arg("name") as? String).orEmpty().trim()
            val name = explicitName.ifBlank { deriveMetaName(parsed, parameters) }
            if (name == null) {
                error(
                    Diagnostic.META_CONTRACT,
                    "LogMeta('$valueExpr') needs an explicit name.\n" +
                        "Fix: LogMeta('$valueExpr', name = \"desiredKey\").",
                    fn,
                )
                return null
            }
            if (!metaNames.add(name)) {
                error(
                    Diagnostic.META_CONTRACT,
                    "Duplicate metadata name '$name' in @Log of '${fn.simpleName.asString()}'.\n" +
                        "Fix: remove one of the duplicates.",
                    fn,
                )
                return null
            }
            metadata.add(
                ResolvedMeta(
                    name = name,
                    expr = parsed,
                    sensitiveMode = resolvedMode,
                    extractorFqn = extractorFqn,
                    adapterFqn = adapterFqn,
                    extractorInjected = inject,
                    extractorIsObject = extractor?.isObject == true,
                ),
            )
        }

        // ---- changes -----------------------------------------------------------------------
        val changeAnnotations = annotationEntries(annotation.arg("changes"), Ks.LOG_CHANGE_FQN)
        val changes = ArrayList<ResolvedChange>(changeAnnotations.size)
        val fieldsSeen = HashSet<String>()
        for (entry in changeAnnotations) {
            val field = (entry.arg("field") as? String).orEmpty().trim()
            if (field.isBlank()) {
                error(
                    Diagnostic.CHANGE_CONTRACT,
                    "LogChange requires a non-blank field name.\n" +
                        "Fix: use changes = [LogChange(field = \"title\", from = ..., to = ...)].",
                    fn,
                )
                return null
            }
            if (!fieldsSeen.add(field)) {
                error(
                    Diagnostic.CHANGE_CONTRACT,
                    "Duplicate LogChange field '$field'.\n" +
                        "Fix: remove the duplicate to keep events deterministic.",
                    fn,
                )
                return null
            }
            val fromExpr = (entry.arg("from") as? String).orEmpty()
            val toExpr = (entry.arg("to") as? String).orEmpty()
            val from = resolveExpr(fromExpr, parameters, returnType, "LogChange.from of field '$field'", fn, allowResult = true) ?: return null
            val to = resolveExpr(toExpr, parameters, returnType, "LogChange.to of field '$field'", fn, allowResult = true) ?: return null
            for ((expr, direction) in listOf(from to "from", to to "to")) {
                if (expr.isChain) {
                    val blaze = validateChain(expr, parameters, returnType, SensitiveMode.CHANGE, fn) ?: return null
                    if (!Ks.isLoggableValueType(blaze.terminal)) {
                        error(
                            Diagnostic.CHANGE_CONTRACT,
                            "Unsupported LogChange value type '${Ks.simpleName(blaze.terminal)}' for field " +
                                "'$field' ($direction: $fromExpr).\n" +
                                "Fix: compare loggable values only (numbers, booleans, strings, enums).",
                            fn,
                        )
                        return null
                    }
                }
            }
            changes.add(
                ResolvedChange(
                    field = field,
                    before = from,
                    after = to,
                    onlyIfChanged = entry.arg("onlyIfChanged") as? Boolean == true,
                ),
            )
        }

        // ---- auto changes ------------------------------------------------------------------
        if (autoEnabled) {
            val auto = if (autoPair != null) {
                buildAutoChanges(
                    entityType = autoPair.entityType,
                    beforeSource = autoPair.before,
                    afterSource = autoPair.after,
                    parameters = parameters,
                    returnType = returnType,
                    explicitFields = fieldsSeen,
                    required = autoRequired,
                    fn = fn,
                ) ?: return null
            } else {
                autoShapeError(autoRequired, fn) ?: return null
            }
            changes.addAll(auto)
        }

        // Flow-returning functions emit start/terminal events only: there is no
        // single result value, so result-headed selections cannot render.
        if (flowReturn) {
            val resultOffender = listOfNotNull(entityId, actor)
                .plus(metadata.map { it.expr })
                .plus(changes.flatMap { listOf(it.before, it.after) })
                .firstOrNull { it.usesResult }
            if (resultOffender != null) {
                error(
                    Diagnostic.FUNCTION_SHAPE,
                    "Flow-returning '${fn.simpleName.asString()}' has no single result value: " +
                        "'${exprSource(resultOffender)}' cannot be selected.\n" +
                        "Fix: select parameters or constants only — flow instrumentation reports the " +
                        "subscription and the terminal event, not per-value results.",
                    fn,
                )
                return null
            }
        }

        var usesContext: Boolean =
            metadata.any { it.expr is ResolvedExpr.ContextOf || it.expr is ResolvedExpr.ActorOf } ||
                entityId is ResolvedExpr.ContextOf ||
                actor is ResolvedExpr.ContextOf ||
                changes.any { it.before is ResolvedExpr.ContextOf || it.after is ResolvedExpr.ContextOf }

        return ResolvedOp(
            fn = fn,
            containerDecl = containerDecl,
            containerPackage = containerPackage,
            extensionReceiverType = extensionReceiverType,
            suspendFn = suspendFn,
            unitReturn = unitReturn,
            returnType = returnType,
            parameters = parameters,
            action = action,
            actionVersion = actionVersion,
            level = level,
            category = category,
            dispatch = dispatch,
            sampling = sampling,
            entityName = entityName,
            entityId = entityId,
            actor = actor,
            actorSensitiveMode = actorSensitiveMode,
            metadata = metadata,
            changes = changes,
            before = before,
            onSuccess = onSuccess,
            onFailure = onFailure,
            onCancellation = onCancellation,
            description = description,
            source = containerDecl?.simpleName?.asString()
                ?.let { "$it.${fn.simpleName.asString()}" }
                ?: fn.simpleName.asString(),
            usesContext = usesContext,
        )
    }

    // ------------------------------------------------------------------ helpers

    /** Where an auto-captured field's before/after values come from. */
    private sealed class AutoSource {
        abstract fun expr(field: String): ResolvedExpr

        class Parameter(val index: Int, val name: String) : AutoSource() {
            override fun expr(field: String): ResolvedExpr =
                ResolvedExpr.Param(index, name, listOf(field))
        }

        object Result : AutoSource() {
            override fun expr(field: String): ResolvedExpr = ResolvedExpr.Result(listOf(field))
        }
    }

    private class AutoPair(
        val entityType: KSType,
        val before: AutoSource,
        val after: AutoSource,
    )

    /** Entity id selected from the auto pair's "after" object, when it has one. */
    private fun autoEntityId(pair: AutoPair?): ResolvedExpr? {
        if (pair == null) return null
        val declaration = pair.entityType.declaration as? KSClassDeclaration ?: return null
        val idProperty = Ks.idPropertyOf(declaration) ?: return null
        val id = idProperty.simpleName.asString()
        return when (val after = pair.after) {
            is AutoSource.Parameter -> ResolvedExpr.Param(after.index, after.name, listOf(id))
            AutoSource.Result -> ResolvedExpr.Result(listOf(id))
        }
    }

    /**
     * Infers the before/after objects for automatic capture.
     *
     * Accepted shapes: two eligible parameters of the same entity type (first
     * is before, second is after), or one eligible entity parameter plus an
     * entity-typed return value. Ambiguous or unsupported signatures return
     * null; the caller decides whether that is an error (explicit ALL) or a
     * silent skip (module-wide default), so enabling it globally cannot break
     * unrelated reads/deletes.
     */
    private fun inferAutoPair(
        parameters: List<ParameterModel>,
        returnType: KSType,
        flowReturn: Boolean,
    ): AutoPair? {
        val eligible = parameters.filter {
            !it.vararg && !it.excluded && !it.sensitive && isDiffableEntityType(it.type)
        }

        val pairGroups = eligible.groupBy { typeKey(it.type) }.values.filter { it.size >= 2 }
        if (pairGroups.size > 1) return null
        val pair = pairGroups.firstOrNull()
        if (pair != null) {
            if (pair.size > 2) return null
            return AutoPair(
                entityType = pair[0].type,
                before = AutoSource.Parameter(pair[0].index, pair[0].name),
                after = AutoSource.Parameter(pair[1].index, pair[1].name),
            )
        }

        val resultType = if (!flowReturn && !Ks.isUnit(returnType)) {
            Ks.unwrapResult(returnType).first
        } else {
            null
        }
        if (resultType != null && isDiffableEntityType(resultType)) {
            val matches = eligible.filter { typeKey(it.type) == typeKey(resultType) }
            if (matches.size == 1) {
                return AutoPair(
                    entityType = resultType,
                    before = AutoSource.Parameter(matches[0].index, matches[0].name),
                    after = AutoSource.Result,
                )
            }
        }
        return null
    }

    private fun autoShapeError(required: Boolean, fn: KSFunctionDeclaration): List<ResolvedChange>? {
        if (!required) return emptyList()
        error(
            Diagnostic.CHANGE_CONTRACT,
            "Cannot auto-capture changes for '${fn.simpleName.asString()}': the signature provides no single " +
                "before/after entity pair.\n" +
                "Fix: declare two parameters of the same entity type (before first, after second), or take one " +
                "entity parameter and return that entity type; otherwise set autoChanges = OFF and select fields " +
                "with changes = [LogChange(...)].",
            fn,
        )
        return null
    }

    private fun buildAutoChanges(
        entityType: KSType,
        beforeSource: AutoSource,
        afterSource: AutoSource,
        parameters: List<ParameterModel>,
        returnType: KSType,
        explicitFields: Set<String>,
        required: Boolean,
        fn: KSFunctionDeclaration,
    ): List<ResolvedChange>? {
        val declaration = entityType.declaration as? KSClassDeclaration
        if (declaration == null) {
            return autoShapeError(required, fn)
        }
        val properties = declaration.getAllProperties()
            .distinctBy { it.simpleName.asString() }
            .filter { it.simpleName.asString() !in explicitFields && isAutoCandidate(it) }
            .sortedBy { it.simpleName.asString() }
            .toList()
        if (properties.isEmpty()) {
            if (required && explicitFields.isEmpty()) {
                error(
                    Diagnostic.CHANGE_CONTRACT,
                    "No auto-capturable fields remain on '${Ks.simpleName(entityType)}': every property is " +
                        "private, non-loggable, @LogSensitive or @LogExclude.\n" +
                        "Fix: use explicit changes with non-sensitive loggable values, or remove autoChanges = ALL.",
                    fn,
                )
                return null
            }
            return emptyList()
        }

        val result = ArrayList<ResolvedChange>(properties.size)
        for (property in properties) {
            val field = property.simpleName.asString()
            val before = beforeSource.expr(field)
            val after = afterSource.expr(field)
            val beforeInfo = validateChain(before, parameters, returnType, SensitiveMode.CHANGE, fn)
                ?: return null
            val afterInfo = validateChain(after, parameters, returnType, SensitiveMode.CHANGE, fn)
                ?: return null
            if (!Ks.isLoggableValueType(beforeInfo.terminal) || !Ks.isLoggableValueType(afterInfo.terminal)) {
                continue
            }
            result += ResolvedChange(field, before, after, onlyIfChanged = true)
        }
        return result
    }

    private fun isAutoCandidate(property: KSPropertyDeclaration): Boolean {
        if (property.modifiers.any { it == Modifier.PRIVATE || it == Modifier.PROTECTED }) return false
        if (Ks.isExcluded(property) || Ks.isSensitive(property)) return false
        if (Ks.suspiciousName(property.simpleName.asString())) return false
        val type = property.type.resolve()
        if (type.isError || Ks.isSensitiveType(type) || Ks.isExcludedType(type)) return false
        return Ks.isLoggableValueType(type)
    }

    private fun isDiffableEntityType(type: KSType): Boolean {
        if (type.isError) return false
        if (Ks.unwrapCollection(type).second || Ks.isArrayType(type)) return false
        return Ks.domainCandidate(type, domainPackages) != null
    }

    /** Structural key used to pair parameters/result while ignoring nullability. */
    private fun typeKey(type: KSType): String {
        val base = Ks.fqnOfType(type)
        if (type.arguments.isEmpty()) return base
        return buildString {
            append(base)
            append('<')
            type.arguments.forEachIndexed { index, argument ->
                if (index > 0) append(',')
                append(argument.type?.resolve()?.let { Ks.fqnOfType(it) } ?: "*")
            }
            append('>')
        }
    }

    /**
     * Walks the chain, reporting missing properties, exclusions and sensitive
     * values per [mode]. Returns terminal info with resolved properties, or
     * null after reporting errors.
     */
    fun validateChain(
        expr: ResolvedExpr,
        parameters: List<ParameterModel>,
        returnType: KSType,
        mode: SensitiveMode,
        fn: KSFunctionDeclaration,
        sensitiveFallback: SensitiveMode? = null,
    ): Blaze? {
        val label = exprSource(expr)
        val rootChain: List<String> = when (expr) {
            is ResolvedExpr.Param -> expr.chain
            is ResolvedExpr.Result -> expr.chain
            else -> return null
        }
        if (expr !is ResolvedExpr.Param && expr !is ResolvedExpr.Result) return null
        var resultUnwrapped = false
        val rootType = when (expr) {
            is ResolvedExpr.Param -> parameters[expr.index].type
            else -> {
                val (unwrapped, wasWrapped) = Ks.unwrapResult(returnType)
                resultUnwrapped = wasWrapped
                unwrapped
            }
        }
        var current = rootType
        val resolved = ArrayList<KSPropertyDeclaration>()
        var sensitiveTouched = false
        var omitted = false

        fun applySensitive(subject: String, node: Any): Boolean {
            sensitiveTouched = true
            // @LogConfig(sensitive = ...) supplies the policy for META_DENY
            // chains that select sensitive values without one.
            val effective =
                if (mode == SensitiveMode.META_DENY && sensitiveFallback != null) sensitiveFallback else mode
            when (effective) {
                SensitiveMode.ID -> error(
                    Diagnostic.SENSITIVE_ID,
                    "'$label' selects $subject marked @LogSensitive; entity ids must never be masked or hashed.\n" +
                        "Fix: pick a different id source.",
                    node,
                )
                SensitiveMode.CHANGE -> error(
                    Diagnostic.SENSITIVE_CHANGE,
                    "LogChange('$label') selects $subject marked @LogSensitive.\n" +
                        "Fix: use non-sensitive fields, or record the value through LogMeta with an explicit " +
                        "MASK/HASH sensitive policy.",
                    node,
                )
                SensitiveMode.META_DENY -> error(
                    Diagnostic.SENSITIVE_POLICY,
                    "'$label' selects $subject marked @LogSensitive without an explicit policy.\n" +
                        "Fix: choose MASK/HASH/OMIT (per selection via LogMeta(...) or container-wide via " +
                        "@LogConfig(sensitive = ...)), or select a different value.",
                    node,
                )
                SensitiveMode.META_OMIT -> {
                    omitted = true
                    return true
                }
                SensitiveMode.META_MASK, SensitiveMode.META_HASH -> Unit // allowance carried to codegen
            }
            return effective == SensitiveMode.META_MASK || effective == SensitiveMode.META_HASH
        }

        // The root of a selection can carry the mark itself: a parameter
        // annotation (`@LogSensitive token`), a type-use annotation
        // (`token: @LogSensitive String`, `(): @LogSensitive User`) or a
        // marked class.
        when (expr) {
            is ResolvedExpr.Param -> {
                val parameter = parameters[expr.index]
                if (parameter.excluded) {
                    error(
                        Diagnostic.PROPERTY_CHAIN,
                        "Parameter '${parameter.name}' is @LogExclude and can never appear in '$label'.\n" +
                            "Fix: remove it from the expression, or relax @LogExclude if this data is legitimately loggable.",
                        fn,
                    )
                    return null
                }
                if (parameter.sensitive && !applySensitive("parameter '${parameter.name}'", fn)) return null
            }
            is ResolvedExpr.Result -> {
                if (Ks.isExcludedType(rootType)) {
                    error(
                        Diagnostic.PROPERTY_CHAIN,
                        "The return value of '${fn.simpleName.asString()}' is @LogExclude and can never appear in " +
                            "'$label'.\nFix: remove it from the expression, or relax @LogExclude if this data is " +
                            "legitimately loggable.",
                        fn,
                    )
                    return null
                }
                if (Ks.isSensitiveType(rootType) && !applySensitive("the return value", fn)) return null
            }
            else -> Unit
        }

        for (segmentName in rootChain) {
            val declaration = current.declaration as? KSClassDeclaration
            val property = declaration?.getAllProperties()?.firstOrNull { it.simpleName.asString() == segmentName }
            if (property == null) {
                error(
                    Diagnostic.PROPERTY_CHAIN,
                    "Requested property '$segmentName' does not exist on '${Ks.simpleName(current)}' (in '$label').\n" +
                        "Fix: correct the property chain.",
                    fn,
                )
                return null
            }
            if (Ks.isExcluded(property)) {
                error(
                    Diagnostic.PROPERTY_CHAIN,
                    "Property '${property.simpleName.asString()}' is @LogExclude and can never appear in '$label'.\n" +
                        "Fix: remove it from the expression, or relax @LogExclude if this data is legitimately loggable.",
                    property,
                )
                return null
            }
            if (Ks.isSensitive(property)) {
                if (!applySensitive("value of '${property.simpleName.asString()}'", property)) return null
            } else if (Ks.suspiciousName(property.simpleName.asString())) {
                warn(
                    Diagnostic.SUSPICIOUS_NAME,
                    "Property '${property.simpleName.asString()}' looks sensitive by name but is not marked " +
                        "(@LogSensitive/@LogExclude). It is logged only because you selected it explicitly.\n" +
                        "Fix: confirm the selection, or mark the property @LogSensitive/@LogExclude.",
                    fn,
                )
            }
            current = property.type.resolve()
            if (current.isError) return null
            resolved.add(property)
        }
        when (expr) {
            is ResolvedExpr.Param -> expr.chainInfo = ChainInfo(resolved)
            is ResolvedExpr.Result -> expr.chainInfo = ChainInfo(resolved, resultUnwrapped)
            else -> Unit
        }
        return Blaze(
            terminal = current,
            properties = resolved,
            sensitiveTouched = sensitiveTouched,
            omitted = omitted,
        )
    }

    /**
     * Annotation enum values arrive as KSType (KSP1) or the enum-entry
     * declaration/name (KSP2); resolve every representation to the constant's
     * simple name (e.g. `LogLevel.INFO` → `"INFO"`).
     */
    private fun enumConstantName(raw: Any?): String? = when (raw) {
        is KSType -> raw.declaration.qualifiedName?.asString()?.substringAfterLast('.')
        is KSClassDeclaration ->
            (raw.qualifiedName ?: raw.simpleName).asString().substringAfterLast('.')
        is KSName -> raw.asString().substringAfterLast('.')
        else -> null
    }

    private fun exprSource(expr: ResolvedExpr): String = when (expr) {
        is ResolvedExpr.Constant -> "'${expr.text}'"
        is ResolvedExpr.Param -> (listOf(expr.param) + expr.chain).joinToString(".")
        is ResolvedExpr.Result -> (listOf("result") + expr.chain).joinToString(".")
        is ResolvedExpr.ActorOf -> listOfNotNull("actor", expr.access).joinToString(".")
        is ResolvedExpr.ContextOf -> "context.${expr.key}"
        is ResolvedExpr.None -> "none"
    }

    /** Parses a source expression, validating its head (result/actor/context/parameter). */
    private fun resolveExpr(
        raw: String,
        parameters: List<ParameterModel>,
        returnType: KSType,
        purpose: String,
        fn: KSFunctionDeclaration,
        allowResult: Boolean,
    ): ResolvedExpr? {
        val text = raw.trim()
        if (text.isBlank()) {
            error(
                Diagnostic.EXPRESSION_SYNTAX,
                "$purpose expression must not be empty.\n" +
                    "Fix: use result, actor, context.<key>, a parameter name, a property chain or a quoted constant.",
                fn,
            )
            return null
        }
        if (text.length >= 2 && text.startsWith("'") && text.endsWith("'")) {
            return ResolvedExpr.Constant(text.substring(1, text.length - 1))
        }
        val head = text.substringBefore('.')
        val rest = if (text.contains('.')) text.substringAfter('.', "") else ""
        val chain = if (rest.isBlank()) emptyList() else rest.split('.')
        when (head) {
            "result" -> {
                if (!allowResult) {
                    error(
                        Diagnostic.EXPRESSION_SYNTAX,
                        "$purpose cannot use 'result' — it is unavailable in this position.\n" +
                            "Fix: select a parameter or a constant instead.",
                        fn,
                    )
                    return null
                }
                if (Ks.isUnit(returnType)) {
                    error(
                        Diagnostic.EXPRESSION_SYNTAX,
                        "$purpose requests '${"result${if (rest.isNotBlank()) ".$rest" else ""}"}' but " +
                            "'${fn.simpleName.asString()}' returns Unit; there is no result value.\n" +
                            "Fix: use a parameter-based source or a constant.",
                        fn,
                    )
                    return null
                }
                return ResolvedExpr.Result(chain)
            }
            "actor" -> {
                if (chain.size > 1) {
                    error(
                        Diagnostic.EXPRESSION_SYNTAX,
                        "$purpose: 'actor' source supports one accessor (actor, actor.id, actor.name, actor.type, actor.<attr>).\n" +
                            "Fix: use a single accessor, or select an explicit parameter.",
                        fn,
                    )
                    return null
                }
                return ResolvedExpr.ActorOf(chain.firstOrNull())
            }
            "context" -> {
                if (chain.size != 1 || chain[0].isBlank()) {
                    error(
                        Diagnostic.EXPRESSION_SYNTAX,
                        "$purpose: 'context' source requires exactly one key: context.<key>.\n" +
                            "Fix: reference context.<key>, e.g. context.requestId.",
                        fn,
                    )
                    return null
                }
                return ResolvedExpr.ContextOf(chain.first())
            }
            else -> {
                val parameter = parameters.firstOrNull { it.name == head }
                if (parameter == null) {
                    val known = parameters.joinToString { it.name }.ifBlank { "<none — function has no parameters>" }
                    error(
                        Diagnostic.EXPRESSION_SYNTAX,
                        "$purpose: unknown source '$head'. Known sources: result, actor, context.<key>, " +
                            "parameter names ($known).\nFix: correct the expression.",
                        fn,
                    )
                    return null
                }
                if (parameter.vararg && chain.isNotEmpty()) {
                    error(
                        Diagnostic.EXPRESSION_SYNTAX,
                        "$purpose: vararg parameter '${parameter.name}' cannot provide chained values " +
                            "('$head.$rest') — it arrives as an expanded array.\n" +
                            "Fix: use the parameter itself (with LogMeta extractor = ... to convert it), " +
                            "or select an unrelated value.",
                        fn,
                    )
                    return null
                }
                return ResolvedExpr.Param(index = parameter.index, param = head, chain = chain)
            }
        }
    }

    private fun deriveMetaName(expr: ResolvedExpr, parameters: List<ParameterModel>): String? = when (expr) {
        is ResolvedExpr.Param ->
            if (expr.chain.isEmpty()) parameters[expr.index].name else expr.chain.last()
        is ResolvedExpr.Result ->
            if (expr.chain.isEmpty()) "result" else expr.chain.last()
        is ResolvedExpr.ContextOf -> expr.key
        is ResolvedExpr.ActorOf -> if (expr.access == null) "actor" else "actor.${expr.access}"
        else -> null
    }

    /** Entity-id inference; only reports what it can prove. Returns null after errors. */
    private fun inferEntityId(
        parameters: List<ParameterModel>,
        returnType: KSType,
        fn: KSFunctionDeclaration,
        roomKind: String?,
    ): ResolvedExpr? {
        val insertShaped = when (roomKind) {
            "insert", "upsert" -> true
            null -> INSERT_SHAPED.containsMatchIn(fn.simpleName.asString())
            else -> false
        }
        if (insertShaped) {
            val unwrappedReturn = Ks.unwrapResult(returnType).first
            if (Ks.isIdType(unwrappedReturn)) return ResolvedExpr.Result(chain = emptyList())
            if (isListOfIdType(unwrappedReturn)) {
                // batch insert: multiple ids; a single entityId is not provable
                return ResolvedExpr.None
            }
            // otherwise continue to parameter-based inference
        }

        val domainParams = parameters.mapNotNull { p -> Ks.domainCandidate(p.type, domainPackages)?.let { p to it } }
        if (domainParams.size > 1) {
            error(
                Diagnostic.ENTITY_ID_INFERENCE,
                "Cannot infer entity ID: ${domainParams.size} entity parameters " +
                    "(${domainParams.joinToString { it.first.name }}) are equally plausible. ILogger never guesses.\n" +
                    "Fix: specify @Log(entityId = \"...\") naming the parameter/property, e.g. @Log(entityId = \"task.id\").",
                fn,
            )
            return null
        }

        val candidates = ArrayList<Pair<ParameterModel, KSPropertyDeclaration?>>()
        for ((param, type) in domainParams) {
            // collection-valued params (batch ops) do not provide a provable single id
            if (Ks.unwrapCollection(param.type).second) continue
            val declaration = type.declaration as? KSClassDeclaration ?: continue
            val idProperty = Ks.idPropertyOf(declaration)
            if (idProperty != null) candidates.add(param to idProperty)
        }
        val idParams = parameters.filter { Ks.isIdType(it.type) }
        if (domainParams.isEmpty()) {
            if (idParams.size == 1) {
                candidates.add(idParams.first() to null)
            } else if (idParams.size > 1) {
                val idish = idParams.filter { it.name.lowercase() == "id" || it.name.lowercase().endsWith("id") }
                if (idish.size == 1) {
                    candidates.add(idish.first() to null)
                } else {
                    error(
                        Diagnostic.ENTITY_ID_INFERENCE,
                        "Cannot infer entity ID: ${idParams.size} id-typed parameters " +
                            "(${idParams.joinToString { it.name }}) are equally plausible. ILogger never guesses.\n" +
                            "Fix: specify @Log(entityId = \"...\") explicitly.",
                        fn,
                    )
                    return null
                }
            }
        }

        return when (candidates.size) {
            1 -> {
                val (param, property) = candidates.first()
                ResolvedExpr.Param(
                    index = param.index,
                    param = param.name,
                    chain = if (property != null) listOf(property.simpleName.asString()) else emptyList(),
                )
            }
            0 -> {
                error(
                    Diagnostic.ENTITY_ID_INFERENCE,
                    "Cannot infer entity ID of '${fn.simpleName.asString()}'. No provable source: the result is not " +
                        "an id, no entity id property (named 'id', or marked @LogEntityId / Room's @PrimaryKey), and " +
                        "no single id-typed parameter.\n" +
                        "Fix: specify @Log(entityId = \"...\") explicitly.",
                    fn,
                )
                null
            }
            else -> {
                error(
                    Diagnostic.ENTITY_ID_INFERENCE,
                    "Cannot infer entity ID: ambiguous candidates (${candidates.joinToString { it.first.name }}).\n" +
                        "ILogger never guesses.\nFix: specify @Log(entityId = \"...\") explicitly.",
                    fn,
                )
                null
            }
        }
    }

    private fun isListOfIdType(type: KSType): Boolean {
        val (element, wasCollection) = Ks.unwrapCollection(type)
        return wasCollection && Ks.isIdType(element)
    }

    private fun hasExtractorRef(entry: KSAnnotation): Boolean {
        val extractor = entry.arg("extractor") as? KSType ?: return false
        return !Ks.isNotSuppliedType(extractor)
    }

    private fun hasAdapterRef(entry: KSAnnotation): Boolean {
        val adapter = entry.arg("adapter") as? KSType ?: return false
        return !Ks.isNotSuppliedType(adapter)
    }

    private fun validateAdapter(entry: KSAnnotation): String? {
        val adapter = entry.arg("adapter") as? KSType ?: return null
        val declaration = adapter.declaration as? KSClassDeclaration
        if (declaration == null) {
            error(
                Diagnostic.META_CONVERTER,
                "LogMeta(adapter = ...) must be a class reference implementing io.ilogger.core.ValueAdapter.\n" +
                    "Fix: pass a concrete class, e.g. adapter = MyAdapter::class.",
                entry,
            )
            return null
        }
        if (!implementsValueAdapter(declaration)) {
            error(
                Diagnostic.META_CONVERTER,
                "LogMeta(adapter = ${declaration.simpleName.asString()}::class) must implement " +
                    "io.ilogger.core.ValueAdapter.\n" +
                    "Fix: implement ValueAdapter, or remove the adapter and select loggable properties.",
                declaration,
            )
            return null
        }
        val ctor = declaration.primaryConstructor
        if (ctor != null && ctor.parameters.isNotEmpty()) {
            error(
                Diagnostic.META_CONVERTER,
                "LogMeta(adapter = ${declaration.simpleName.asString()}::class) requires a no-argument " +
                    "constructor.\nFix: provide an empty primary constructor.",
                declaration,
            )
            return null
        }
        return declaration.qualifiedName?.asString()
    }

    private fun implementsValueAdapter(declaration: KSClassDeclaration): Boolean {
        val queue = ArrayDeque<KSClassDeclaration>()
        queue.add(declaration)
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val fqn = current.qualifiedName?.asString() ?: continue
            if (!seen.add(fqn)) continue
            if (fqn == "io.ilogger.core.ValueAdapter") return true
            for (superType in current.superTypes) {
                val sup = superType.resolve().declaration as? KSClassDeclaration ?: continue
                queue.add(sup)
            }
        }
        return false
    }

    private class ExtractorRef(val fqn: String, val isObject: Boolean)

    private fun validateExtractor(entry: KSAnnotation, inject: Boolean): ExtractorRef? {
        val extractor = entry.arg("extractor") as? KSType ?: return null
        val declaration = extractor.declaration as? KSClassDeclaration
        if (declaration == null) {
            error(
                Diagnostic.META_CONVERTER,
                "LogMeta(extractor = ...) must be a class reference implementing io.ilogger.core.LogValueExtractor.\n" +
                    "Fix: pass a concrete class, e.g. extractor = MyExtractor::class.",
                entry,
            )
            return null
        }
        if (!implementsExtractor(declaration)) {
            error(
                Diagnostic.META_CONVERTER,
                "LogMeta(extractor = ${declaration.simpleName.asString()}::class) must implement " +
                    "io.ilogger.core.LogValueExtractor.\n" +
                    "Fix: implement LogValueExtractor<T>, or remove the extractor and select loggable properties.",
                declaration,
            )
            return null
        }
        val name = declaration.simpleName.asString()
        val fqn = declaration.qualifiedName?.asString() ?: return null
        val isObject = declaration.classKind == ClassKind.OBJECT
        if (inject) {
            // Registered through ILogger.extractors; no construction here.
            return ExtractorRef(fqn, isObject)
        }
        if (isObject) return ExtractorRef(fqn, isObject = true)
        if (declaration.classKind != ClassKind.CLASS || Modifier.ABSTRACT in declaration.modifiers) {
            error(
                Diagnostic.META_CONVERTER,
                "LogMeta(extractor = $name::class) cannot be instantiated by generated code.\n" +
                    "Fix: register a concrete instance with inject = true and " +
                    "ILogger.create { extractor($name::class) { ... } }.",
                declaration,
            )
            return null
        }
        if (Modifier.INNER in declaration.modifiers) {
            error(
                Diagnostic.META_CONVERTER,
                "LogMeta(extractor = $name::class) is an inner class and cannot be instantiated by generated code.\n" +
                    "Fix: make it a top-level or nested class, or register it with inject = true.",
                declaration,
            )
            return null
        }
        val primary = declaration.primaryConstructor
        val constructible = primary == null || primary.parameters.all { it.hasDefault }
        if (!constructible) {
            error(
                Diagnostic.META_CONVERTER,
                "LogMeta(extractor = $name::class) needs a public no-argument constructor for generated code.\n" +
                    "Fix: provide one, or declare LogMeta(..., inject = true) and register a factory with " +
                    "ILogger.create { extractor($name::class) { $name(...) } }.",
                declaration,
            )
            return null
        }
        return ExtractorRef(fqn, isObject = false)
    }

    private fun implementsExtractor(declaration: KSClassDeclaration): Boolean {
        val queue = ArrayDeque<KSClassDeclaration>()
        queue.add(declaration)
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val fqn = current.qualifiedName?.asString() ?: continue
            if (!seen.add(fqn)) continue
            if (fqn == "io.ilogger.core.LogValueExtractor") return true
            for (superType in current.superTypes) {
                val sup = superType.resolve().declaration as? KSClassDeclaration ?: continue
                queue.add(sup)
            }
        }
        return false
    }

    internal class Blaze internal constructor(
        val terminal: KSType,
        val properties: List<KSPropertyDeclaration>,
        val sensitiveTouched: Boolean = false,
        /** True when a sensitive target was reached under an OMIT policy. */
        val omitted: Boolean = false,
    )

    private companion object {
        val LOG_LEVELS = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "AUDIT")
        val DISPATCH_MODES = setOf("DEFAULT", "INLINE", "ASYNC", "BUFFERED")
        val SENSITIVE_MODES = setOf("DENY", "OMIT", "MASK", "HASH")
        val AUTO_CHANGES_MODES = setOf("DEFAULT", "OFF", "ALL")
        val INSERT_SHAPED = Regex("^(insert|create|add|register)", RegexOption.IGNORE_CASE)
    }
}

internal fun KSAnnotation.arg(name: String): Any? =
    arguments.firstOrNull { it.name?.asString() == name }?.value

private fun annotationEntries(arg: Any?, fqn: String): List<KSAnnotation> =
    (arg as? List<*>)?.filterIsInstance<KSAnnotation>()?.filter { Ks.fqnOf(it) == fqn }.orEmpty()
