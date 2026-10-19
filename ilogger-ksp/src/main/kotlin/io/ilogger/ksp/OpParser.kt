package io.ilogger.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

/**
 * Parses and validates one `@Log`-annotated function into [ResolvedOp].
 * Validation failures produce compile diagnostics; the function is skipped
 * ("never guess" — spec §48–49).
 */
internal class OpParser(
    private val error: (message: String, symbol: Any) -> Unit,
    private val warn: (message: String, symbol: Any) -> Unit,
) {

    fun parse(fn: KSFunctionDeclaration, annotation: KSAnnotation, roomKind: String?): ResolvedOp? {
        // ---- enclosing declaration -------------------------------------------
        val enclosing = fn.parentDeclaration
        if (enclosing == null || enclosing !is KSClassDeclaration || enclosing.classKind != ClassKind.INTERFACE) {
            error(
                "@Log must be applied to a function of an interface. ILogger generates a delegating " +
                    "implementation for the entire interface (DAOs, repositories), which is only possible for interfaces.\n" +
                    "Fix: move '${fn.simpleName.asString()}' into an interface.",
                fn,
            )
            return null
        }
        if (enclosing.typeParameters.isNotEmpty()) {
            error(
                "ilogger-ksp v1 does not support generic interfaces.\n" +
                    "Fix: remove type parameters or instrument the call site with ILogger.record(...).",
                fn,
            )
            return null
        }
        if (fn.typeParameters.isNotEmpty()) {
            error(
                "@Log cannot instrument generic functions.\n" +
                    "Fix: use a concrete-signature overload, or log at the call site with ILogger.record(...).",
                fn,
            )
            return null
        }
        val interfacePackage = enclosing.packageName.asString()
        if (interfacePackage.isBlank()) {
            error(
                "Interfaces in the default package cannot receive generated wrappers.\n" +
                    "Fix: move the interface into a named package.",
                fn,
            )
            return null
        }

        // ---- scalar annotation fields ------------------------------------------
        val action = (annotation.arg("action") as? String) ?: ""
        if (action.isBlank()) {
            error("@Log requires a non-blank action.\nFix: @Log(\"task.created\") or @Log(TaskActions.CREATED).", fn)
            return null
        }
        if (!action.matches(ACTION_REGEX)) {
            error(
                "Invalid action '$action'. Actions must be dotted canonical codes such as 'task.created'.\n" +
                    "Fix: use '<domain>.<event>[.<variant>]' with no spaces, quotes or special characters.",
                fn,
            )
            return null
        }
        val actionVersion = (annotation.arg("schemaVersion") as? Int) ?: 1
        if (actionVersion < 1) {
            error("@Log(schemaVersion = $actionVersion) is invalid: schemaVersion must be >= 1.", fn)
            return null
        }
        val level = annotation.arg("level")?.let { enumConstantName(it) } ?: "AUDIT"
        if (level !in LOG_LEVELS) {
            error("@Log(level = ...) is invalid: '$level' is not a member of io.ilogger.annotations.LogLevel.", fn)
            return null
        }
        val category = (annotation.arg("category") as? String).orEmpty().trim()
        val dispatch = annotation.arg("dispatch")?.let { enumConstantName(it) } ?: "DEFAULT"
        if (dispatch !in DISPATCH_MODES) {
            error("@Log(dispatch = ...) is invalid: '$dispatch' is not a member of io.ilogger.annotations.DispatchMode.", fn)
            return null
        }
        val before = (annotation.arg("before") as? Boolean) ?: false
        val onSuccess = (annotation.arg("onSuccess") as? Boolean) ?: true
        val onFailure = (annotation.arg("onFailure") as? Boolean) ?: false
        if (!before && !onSuccess && !onFailure) {
            error(
                "@Log would never emit an event (before=false, onSuccess=false, onFailure=false).\n" +
                    "Fix: enable at least one of before/onSuccess/onFailure.",
                fn,
            )
            return null
        }
        val sampling = (annotation.arg("sampling") as? Double) ?: -1.0
        if (sampling != -1.0 && sampling !in 0.0..1.0) {
            error(
                "@Log(sampling = $sampling) is invalid: use -1.0 (inherit) or a rate within [0.0..1.0].\n" +
                    "Fix: remove the override or use a rate in [0.0..1.0].",
                fn,
            )
            return null
        }
        if (sampling != -1.0 && sampling < 1.0 && level == "AUDIT") {
            error(
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
        if (dispatch == "INLINE" && !suspendFn) {
            error(
                "@Log(dispatch = INLINE) cannot instrument non-suspend functions: INLINE delivery requires " +
                    "the suspend API record().\nFix: make the function suspend, or remove the dispatch override.",
                fn,
            )
            return null
        }
        val parameters = ArrayList<ParameterModel>(fn.parameters.size)
        for ((index, parameter) in fn.parameters.withIndex()) {
            val name = parameter.name?.asString()
            if (name.isNullOrBlank()) {
                error(
                    "Parameter #$index has no name (unsupported form) in function '${fn.simpleName.asString()}'.",
                    fn,
                )
                return null
            }
            val type = parameter.type.resolve()
            if (type.isError) {
                error("Parameter '$name' has an unresolved type; @Log cannot instrument it.", fn)
                return null
            }
            parameters.add(
                ParameterModel(
                    name = name,
                    index = index,
                    type = type,
                    vararg = parameter.isVararg,
                ),
            )
        }
        val returnType = fn.returnType?.resolve()
        if (returnType == null || returnType.isError) {
            error(
                "Function '${fn.simpleName.asString()}' lacks an explicit, resolvable return type.\n" +
                    "Fix: declare the return type explicitly (Unit counts).",
                fn,
            )
            return null
        }
        val unitReturn = Ks.isUnit(returnType)
        if (Ks.isFlow(returnType)) {
            error(
                "@Log cannot instrument Flow-returning functions: streams have no single success/failure boundary.\n" +
                    "Fix: log flow outcomes at the collection site with ILogger.record(...), or remove @Log.",
                fn,
            )
            return null
        }
        for (parameter in parameters) {
            if (RESERVED_HEADS.contains(parameter.name)) {
                error(
                    "Parameter name '${parameter.name}' collides with a reserved source keyword " +
                        "(result/actor/context).\nFix: rename the parameter.",
                    fn,
                )
                return null
            }
        }

        // ---- entity ----------------------------------------------------------------
        val explicitEntityArg = annotation.arg("entity") as? KSType
        val explicitEntity = explicitEntityArg?.takeIf { !Ks.isNotSuppliedType(it) }
        var entityName: String? = null
        if (explicitEntity != null) {
            if (explicitEntity.isError) {
                error("@Log(entity = ...) references an unresolvable type.", fn)
                return null
            }
            entityName = Ks.simpleName(explicitEntity)
        }
        if (entityName == null) {
            val candidates = sortedSetOf<String>()
            parameters.mapNotNull { Ks.domainCandidate(it.type) }.forEach { candidates.add(Ks.simpleName(it)) }
            if (!unitReturn) Ks.domainCandidate(returnType)?.let { candidates.add(Ks.simpleName(it)) }
            entityName = when (candidates.size) {
                1 -> candidates.first()
                0 -> null
                else -> {
                    error(
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
                    error("@Log(entityId = ...) cannot reference the ambient actor.", fn)
                    return null
                }
            }
            parsed
        } else {
            val inferred = inferEntityId(parameters, returnType, fn, roomKind) ?: return null
            if (inferred.isChain) {
                validateChain(inferred, parameters, returnType, SensitiveMode.ID, fn) ?: return null
            }
            inferred
        }

        // ---- actor override --------------------------------------------------------------
        val explicitActor = (annotation.arg("actor") as? String).orEmpty()
        var actor: ResolvedExpr? = null
        if (explicitActor.isNotBlank()) {
            val parsed = resolveExpr(explicitActor, parameters, returnType, "@Log(actor)", fn, allowResult = false)
                ?: return null
            when (parsed) {
                is ResolvedExpr.ActorOf, is ResolvedExpr.None -> {
                    error("@Log(actor) cannot reference the ambient actor — omit @Log(actor) to use it.", fn)
                    return null
                }
                is ResolvedExpr.Param, is ResolvedExpr.Result ->
                    validateChain(parsed, parameters, returnType, SensitiveMode.ID, fn) ?: return null
                else -> Unit
            }
            actor = parsed
        }

        // ---- metadata -----------------------------------------------------------------------
        val metadataAnnotations = annotationEntries(annotation.arg("metadata"), Ks.LOG_META_FQN)
        val metadata = ArrayList<ResolvedMeta>(metadataAnnotations.size)
        val metaNames = HashSet<String>()
        for (entry in metadataAnnotations) {
            val valueExpr = (entry.arg("value") as? String).orEmpty()
            if (valueExpr.isBlank()) {
                error("LogMeta requires a value expression.\nFix: LogMeta(\"task.priority\") etc.", fn)
                return null
            }
            val parsed = resolveExpr(valueExpr, parameters, returnType, "LogMeta", fn, allowResult = true) ?: return null

            val sensitiveName = entry.arg("sensitive")?.let { enumConstantName(it) } ?: "DENY"
            if (sensitiveName !in SENSITIVE_MODES) {
                error("Invalid LogMeta sensitive = $sensitiveName (use DENY, OMIT, MASK or HASH).", fn)
                return null
            }
            val extractorFqn = if (hasExtractorRef(entry)) validateExtractor(entry) ?: return null else null
            if (extractorFqn != null && sensitiveName in setOf("MASK", "HASH")) {
                error(
                    "LogMeta('$valueExpr') combines an explicit extractor with sensitive = $sensitiveName.\n" +
                        "Fix: keep the extractor (it already defines the log-safe shape), or drop it and use " +
                        "sensitive = $sensitiveName alone.",
                    fn,
                )
                return null
            }

            val mode = when (sensitiveName) {
                "MASK" -> SensitiveMode.META_MASK
                "HASH" -> SensitiveMode.META_HASH
                else -> SensitiveMode.META_DENY
            }
            if (parsed.isChain) {
                val blaze = validateChain(parsed, parameters, returnType, mode, fn) ?: return null
                if (!Ks.isLoggableValueType(blaze.terminal) && extractorFqn == null) {
                    error(
                        "Unsupported metadata type '${Ks.simpleName(blaze.terminal)}' for LogMeta('$valueExpr').\n" +
                            "Fix: select loggable properties (numbers, booleans, strings, enums, one-level lists of " +
                            "those), or provide LogMeta(extractor = SomeExtractor::class) to convert explicitly.",
                        fn,
                    )
                    return null
                }
            } else if (sensitiveName == "OMIT") {
                error("LogMeta('$valueExpr', sensitive = OMIT) contradicts itself — remove the entry instead.", fn)
                return null
            }

            val explicitName = (entry.arg("name") as? String).orEmpty().trim()
            val name = explicitName.ifBlank { deriveMetaName(parsed, parameters) }
            if (name == null) {
                error(
                    "LogMeta('$valueExpr') needs an explicit name.\n" +
                        "Fix: LogMeta('$valueExpr', name = \"desiredKey\").",
                    fn,
                )
                return null
            }
            if (!metaNames.add(name)) {
                error(
                    "Duplicate metadata name '$name' in @Log of '${fn.simpleName.asString()}'.\n" +
                        "Fix: remove one of the duplicates.",
                    fn,
                )
                return null
            }
            metadata.add(ResolvedMeta(name = name, expr = parsed, sensitiveMode = mode, extractorFqn = extractorFqn))
        }

        // ---- changes -----------------------------------------------------------------------
        val changeAnnotations = annotationEntries(annotation.arg("changes"), Ks.LOG_CHANGE_FQN)
        val changes = ArrayList<ResolvedChange>(changeAnnotations.size)
        val fieldsSeen = HashSet<String>()
        for (entry in changeAnnotations) {
            val field = (entry.arg("field") as? String).orEmpty().trim()
            if (field.isBlank()) {
                error("LogChange requires a non-blank field name.", fn)
                return null
            }
            if (!fieldsSeen.add(field)) {
                error(
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
                            "Unsupported LogChange value type '${Ks.simpleName(blaze.terminal)}' for field " +
                                "'$field' ($direction: $fromExpr).\n" +
                                "Fix: compare loggable values only (numbers, booleans, strings, enums).",
                            fn,
                        )
                        return null
                    }
                }
            }
            changes.add(ResolvedChange(field = field, before = from, after = to))
        }

        var usesContext: Boolean =
            metadata.any { it.expr is ResolvedExpr.ContextOf || it.expr is ResolvedExpr.ActorOf } ||
                entityId is ResolvedExpr.ContextOf ||
                actor is ResolvedExpr.ContextOf ||
                changes.any { it.before is ResolvedExpr.ContextOf || it.after is ResolvedExpr.ContextOf }

        return ResolvedOp(
            fn = fn,
            interfaceDecl = enclosing,
            interfacePackage = interfacePackage,
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
            metadata = metadata,
            changes = changes,
            before = before,
            onSuccess = onSuccess,
            onFailure = onFailure,
            description = description,
            source = "${enclosing.simpleName.asString()}.${fn.simpleName.asString()}",
            usesContext = usesContext,
        )
    }

    // ------------------------------------------------------------------ helpers

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
    ): Blaze? {
        val label = exprSource(expr)
        val rootChain: List<String> = when (expr) {
            is ResolvedExpr.Param -> expr.chain
            is ResolvedExpr.Result -> expr.chain
            else -> return null
        }
        if (expr !is ResolvedExpr.Param && expr !is ResolvedExpr.Result) return null
        val rootType = when (expr) {
            is ResolvedExpr.Param -> parameters[expr.index].type
            else -> returnType
        }
        var current = rootType
        val resolved = ArrayList<KSPropertyDeclaration>()
        for (segmentName in rootChain) {
            val declaration = current.declaration as? KSClassDeclaration
            val property = declaration?.getAllProperties()?.firstOrNull { it.simpleName.asString() == segmentName }
            if (property == null) {
                error(
                    "Requested property '$segmentName' does not exist on '${Ks.simpleName(current)}' (in '$label').\n" +
                        "Fix: correct the property chain.",
                    fn,
                )
                return null
            }
            if (Ks.isExcluded(property)) {
                error(
                    "Property '${property.simpleName.asString()}' is @LogExclude and can never appear in '$label'.\n" +
                        "Fix: remove it from the expression, or relax @LogExclude if this data is legitimately loggable.",
                    property,
                )
                return null
            }
            if (Ks.isSensitive(property)) {
                when (mode) {
                    SensitiveMode.ID -> error(
                        "'$label' selects value of '${property.simpleName.asString()}' marked @LogSensitive; " +
                            "entity ids must never be masked or hashed.\nFix: pick a different id source.",
                        property,
                    )
                    SensitiveMode.CHANGE -> error(
                        "LogChange('$label') selects value of '${property.simpleName.asString()}' marked @LogSensitive.\n" +
                            "Fix: use non-sensitive fields, or record the value through LogMeta with an explicit " +
                            "MASK/HASH sensitive policy.",
                        property,
                    )
                    SensitiveMode.META_DENY -> error(
                        "LogMeta('$label') selects value of '${property.simpleName.asString()}' marked @LogSensitive " +
                            "without an explicit policy.\nFix: choose LogMeta(..., sensitive = MASK|HASH) or select a " +
                            "different property.",
                        property,
                    )
                    SensitiveMode.META_MASK, SensitiveMode.META_HASH -> Unit // allowance carried to codegen
                }
                if (mode != SensitiveMode.META_MASK && mode != SensitiveMode.META_HASH) return null
            } else if (Ks.suspiciousName(property.simpleName.asString())) {
                warn(
                    "Property '${property.simpleName.asString()}' looks sensitive by name but is not marked " +
                        "(@LogSensitive/@LogExclude). It is logged only because you selected it explicitly.",
                    fn,
                )
            }
            current = property.type.resolve()
            if (current.isError) return null
            resolved.add(property)
        }
        when (expr) {
            is ResolvedExpr.Param -> expr.chainInfo = ChainInfo(resolved)
            is ResolvedExpr.Result -> expr.chainInfo = ChainInfo(resolved)
            else -> Unit
        }
        return Blaze(terminal = current, properties = resolved)
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
            error("$purpose expression must not be empty.", fn)
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
                        "$purpose cannot use 'result' — it is unavailable in this position.",
                        fn,
                    )
                    return null
                }
                if (Ks.isUnit(returnType)) {
                    error(
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
                        "$purpose: 'actor' source supports one accessor (actor, actor.id, actor.name, actor.type, actor.<attr>).",
                        fn,
                    )
                    return null
                }
                return ResolvedExpr.ActorOf(chain.firstOrNull())
            }
            "context" -> {
                if (chain.size != 1 || chain[0].isBlank()) {
                    error("$purpose: 'context' source requires exactly one key: context.<key>.", fn)
                    return null
                }
                return ResolvedExpr.ContextOf(chain.first())
            }
            else -> {
                val parameter = parameters.firstOrNull { it.name == head }
                if (parameter == null) {
                    val known = parameters.joinToString { it.name }.ifBlank { "<none — function has no parameters>" }
                    error(
                        "$purpose: unknown source '$head'. Known sources: result, actor, context.<key>, " +
                            "parameter names ($known).\nFix: correct the expression.",
                        fn,
                    )
                    return null
                }
                if (parameter.vararg && chain.isNotEmpty()) {
                    error(
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
            if (Ks.isIdType(returnType)) return ResolvedExpr.Result(chain = emptyList())
            if (isListOfIdType(returnType)) {
                // batch insert: multiple ids; a single entityId is not provable
                return ResolvedExpr.None
            }
            // otherwise continue to parameter-based inference
        }

        val domainParams = parameters.mapNotNull { p -> Ks.domainCandidate(p.type)?.let { p to it } }
        if (domainParams.size > 1) {
            error(
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

    private fun validateExtractor(entry: KSAnnotation): String? {
        val extractor = entry.arg("extractor") as? KSType ?: return null
        val declaration = extractor.declaration as? KSClassDeclaration
        if (declaration == null) {
            error("LogMeta(extractor = ...) must be a class reference implementing io.ilogger.core.LogValueExtractor.", entry)
            return null
        }
        if (!implementsExtractor(declaration)) {
            error(
                "LogMeta(extractor = ${declaration.simpleName.asString()}::class) must implement " +
                    "io.ilogger.core.LogValueExtractor.",
                declaration,
            )
            return null
        }
        val ctor = declaration.primaryConstructor
        if (ctor != null && ctor.parameters.isNotEmpty()) {
            error(
                "LogMeta(extractor = ${declaration.simpleName.asString()}::class) requires a no-argument " +
                    "constructor.\nFix: provide an empty primary constructor.",
                declaration,
            )
            return null
        }
        return declaration.qualifiedName?.asString()
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

    internal class Blaze internal constructor(val terminal: KSType, val properties: List<KSPropertyDeclaration>)

    private companion object {
        val ACTION_REGEX = Regex("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)+$")
        val RESERVED_HEADS = setOf("result", "actor", "context")
        val LOG_LEVELS = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "AUDIT")
        val DISPATCH_MODES = setOf("DEFAULT", "INLINE", "ASYNC", "BUFFERED")
        val SENSITIVE_MODES = setOf("DENY", "OMIT", "MASK", "HASH")
        val INSERT_SHAPED = Regex("^(insert|create|add|register)", RegexOption.IGNORE_CASE)
    }
}

internal fun KSAnnotation.arg(name: String): Any? =
    arguments.firstOrNull { it.name?.asString() == name }?.value

private fun annotationEntries(arg: Any?, fqn: String): List<KSAnnotation> =
    (arg as? List<*>)?.filterIsInstance<KSAnnotation>()?.filter { Ks.fqnOf(it) == fqn }.orEmpty()
