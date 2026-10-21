package io.ilogger.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * KSP entry point. Options:
 *
 * - `ilogger.enabled` (default `true`) — master switch; `false` disables all
 *   generation so builds can compile-time disable instrumentation.
 * - `ilogger.verbose` (default `false`) — logs every generated file path.
 * - `ilogger.expect-diagnostics` (default `false`) — fixture mode for the
 *   diagnostics test-suite: invalid `@Log` usages become *warnings* instead
 *   of fatal KSP errors, and all diagnostics are collected into a
 *   `ilogger-diagnostics.txt` report next to the generated sources for the
 *   `ksp-diagnostics` module's assertions.
 */
public class ILoggerProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ILoggerProcessor(environment)
}

internal class ILoggerProcessor(
    private val environment: SymbolProcessorEnvironment,
) : SymbolProcessor {

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        val enabled = environment.options["ilogger.enabled"] ?: "true"
        if (enabled.equals("false", ignoreCase = true)) return emptyList()
        val verbose = environment.options["ilogger.verbose"]?.equals("true", ignoreCase = true) ?: false
        val expectDiagnostics =
            environment.options["ilogger.expect-diagnostics"]?.equals("true", ignoreCase = true) ?: false

        // Single diagnostic funnel. In fixture mode the same text that would
        // be a fatal error becomes a (non-fatal) warning and is additionally
        // recorded for report assertions.
        val diagnostics = ArrayList<String>()
        fun report(message: String, symbol: Any) {
            val label = fileLabelOf(symbol)
            if (label != null) diagnostics += "$label :: $message"
            environment.logger.run {
                if (expectDiagnostics) warn(message, nodeOf(symbol)) else error(message, nodeOf(symbol))
            }
        }

        val parser = OpParser(
            error = { message, symbol -> report(message, symbol) },
            warn = { message, symbol ->
                environment.logger.warn(message, nodeOf(symbol))
            },
        )

        val annotated = resolver.getSymbolsWithAnnotation(Ks.LOG_FQN).toList()
        val logDaoSymbols = resolver.getSymbolsWithAnnotation(Ks.LOG_DAO_FQN).toList()
        if (annotated.isEmpty() && logDaoSymbols.isEmpty()) return emptyList()

        // @LogDao only selects which DAOs the generated database facade wraps;
        // it never synthesizes actions. Methods still need an explicit @Log.
        val enabledDaos = LinkedHashSet<String>()
        val hiltDaos = LinkedHashSet<String>()
        val koinDaos = LinkedHashSet<String>()
        val hiltAvailable = hasHilt(resolver)
        val koinAvailable = hasKoin(resolver)
        for (symbol in logDaoSymbols) {
            val declaration = symbol as? KSClassDeclaration
            if (declaration == null || declaration.classKind != ClassKind.INTERFACE) {
                report(
                    "@LogDao can only annotate an interface. " +
                        "Fix: apply it to the DAO interface (Room's @Dao types are interfaces).",
                    symbol,
                )
                continue
            }
            // Annotations from dependency modules are compile-time input only:
            // never generate a facade into another module's package.
            if (declaration.containingFile == null) continue
            val options = logDaoOptions(declaration)
            val fqn = declaration.qualifiedName?.asString() ?: continue
            if (options.enabled) enabledDaos += fqn
            if (options.enabled && options.hilt) {
                if (hiltAvailable) {
                    hiltDaos += fqn
                } else {
                    report(
                        "@LogDao(hilt = true) requires Hilt on the compilation classpath. " +
                            "Fix: add the Hilt dependencies, or remove `hilt = true`.",
                        symbol,
                    )
                }
            }
            if (options.enabled && options.koin) {
                if (koinAvailable) {
                    koinDaos += fqn
                } else {
                    report(
                        "@LogDao(koin = true) requires Koin on the compilation classpath. " +
                            "Fix: add koin-core, or remove `koin = true`.",
                        symbol,
                    )
                }
            }
        }

        val ops = ArrayList<ResolvedOp>(annotated.size)
        for (symbol in annotated) {
            val fn = symbol as? KSFunctionDeclaration
            if (fn == null) {
                report(
                    "@Log can only annotate functions. " +
                        "Fix: apply @Log to a function of an interface (DAO/repository/use-case).",
                    symbol,
                )
                continue
            }
            val annotation = Ks.annotation(fn, Ks.LOG_FQN) ?: continue
            val op = parser.parse(fn, annotation, roomKindOf(fn))
            if (op != null) ops.add(op)
        }

        val byInterface = ops.groupBy { it.interfaceDecl.qualifiedName?.asString() ?: it.interfaceName }
        for (group in byInterface.values) {
            val interfaceDecl = group.first().interfaceDecl
            val file = Codegen.buildInterfaceFile(interfaceDecl, group)
            file.writeTo(
                environment.codeGenerator,
                Dependencies(aggregating = true, *resolver.getAllFiles().toList().toTypedArray()),
            )
            if (verbose) {
                environment.logger.info(
                    "ilogger-ksp: generated ${file.name} for ${group.size} @Log op(s) " +
                        "(${interfaceDecl.qualifiedName?.asString()}).",
                )
            }
        }

        // Facades are only useful for DAOs that actually have an adapter in this
        // compilation; a @LogDao interface without @Log methods passes through.
        val wrappedDaos = byInterface.values
            .mapNotNull { group -> group.first().interfaceDecl.qualifiedName?.asString() }
            .toSet()
        val automaticallyWrapped = enabledDaos.intersect(wrappedDaos)
        if (automaticallyWrapped.isNotEmpty()) {
            val databases = resolver.getSymbolsWithAnnotation(Ks.ROOM_DB_INSPECTOR)
                .filterIsInstance<KSClassDeclaration>()
                .filter { database -> database.containingFile != null }
            // Hilt and Koin each permit exactly one binding per type. Track the
            // first owner so a DAO exposed by two databases fails with our
            // diagnostic instead of an opaque duplicate-binding error.
            val hiltOwners = HashMap<String, String>()
            val koinOwners = HashMap<String, String>()
            for (database in databases) {
                val accessors = daoAccessors(database, automaticallyWrapped, hiltDaos, koinDaos)
                if (accessors.none { accessor -> accessor.wrapped }) continue
                val databaseFqn = database.qualifiedName?.asString() ?: database.simpleName.asString()
                val file = DaoFacade.build(database, accessors)
                file.writeTo(
                    environment.codeGenerator,
                    Dependencies(aggregating = true, *resolver.getAllFiles().toList().toTypedArray()),
                )
                if (verbose) {
                    environment.logger.info(
                        "ilogger-ksp: generated ${file.name} for " +
                            "${accessors.count { it.wrapped }} @LogDao accessor(s) " +
                            "(${database.qualifiedName?.asString()}).",
                    )
                }

                val hiltAccessors = ArrayList<DaoFacade.Accessor>()
                for (accessor in accessors) {
                    if (!accessor.hilt) continue
                    val owner = hiltOwners.putIfAbsent(accessor.daoFqn, databaseFqn)
                    if (owner == null) {
                        hiltAccessors += accessor
                    } else {
                        report(
                            "Duplicate Hilt binding for DAO '${accessor.daoFqn}': it is already " +
                                "bound by '$owner' and cannot also be bound by '$databaseFqn'. " +
                                "Fix: keep @LogDao(hilt = true) on only one database, " +
                                "or bind the DAO manually with db.logged(logger).",
                            database,
                        )
                    }
                }
                if (hiltAccessors.isNotEmpty()) {
                    val hiltFile = HiltBindings.build(database, hiltAccessors)
                    hiltFile.writeTo(
                        environment.codeGenerator,
                        Dependencies(aggregating = true, *resolver.getAllFiles().toList().toTypedArray()),
                    )
                    if (verbose) {
                        environment.logger.info(
                            "ilogger-ksp: generated ${hiltFile.name} for " +
                                "${hiltAccessors.size} Hilt DAO binding(s) " +
                                "($databaseFqn).",
                        )
                    }
                }

                val koinAccessors = ArrayList<DaoFacade.Accessor>()
                for (accessor in accessors) {
                    if (!accessor.koin) continue
                    val owner = koinOwners.putIfAbsent(accessor.daoFqn, databaseFqn)
                    if (owner == null) {
                        koinAccessors += accessor
                    } else {
                        report(
                            "Duplicate Koin binding for DAO '${accessor.daoFqn}': it is already " +
                                "bound by '$owner' and cannot also be bound by '$databaseFqn'. " +
                                "Fix: keep @LogDao(koin = true) on only one database, " +
                                "or bind the DAO manually with db.logged(logger).",
                            database,
                        )
                    }
                }
                if (koinAccessors.isNotEmpty()) {
                    val koinFile = KoinBindings.build(database, koinAccessors)
                    koinFile.writeTo(
                        environment.codeGenerator,
                        Dependencies(aggregating = true, *resolver.getAllFiles().toList().toTypedArray()),
                    )
                    if (verbose) {
                        environment.logger.info(
                            "ilogger-ksp: generated ${koinFile.name} for " +
                                "${koinAccessors.size} Koin DAO binding(s) " +
                                "($databaseFqn).",
                        )
                    }
                }
            }
        }

        // Written last: binding diagnostics are produced during generation too.
        if (expectDiagnostics) {
            val combined = resolver.getAllFiles().toList().toTypedArray()
            environment.codeGenerator.createNewFile(
                Dependencies(aggregating = true, *combined),
                packageName = "",
                fileName = "ilogger-diagnostics",
                extensionName = "txt",
            ).bufferedWriter().use { writer ->
                diagnostics.distinct().forEach { line -> writer.appendLine(line) }
            }
        }
        return emptyList()
    }

    private class LogDaoOptions(val enabled: Boolean, val hilt: Boolean, val koin: Boolean)

    private fun logDaoOptions(declaration: KSClassDeclaration): LogDaoOptions {
        val annotation = Ks.annotation(declaration, Ks.LOG_DAO_FQN)
            ?: return LogDaoOptions(enabled = false, hilt = false, koin = false)
        val arguments = annotation.arguments.associateBy { argument -> argument.name?.asString() }
        return LogDaoOptions(
            enabled = arguments["enabled"]?.value as? Boolean ?: true,
            hilt = arguments["hilt"]?.value as? Boolean ?: false,
            koin = arguments["koin"]?.value as? Boolean ?: false,
        )
    }

    /** Hilt is optional: bindings are only generated when its annotations resolve. */
    private fun hasHilt(resolver: Resolver): Boolean = listOf(
        "dagger.Module",
        "dagger.Provides",
        "dagger.hilt.InstallIn",
        "dagger.hilt.components.SingletonComponent",
        "javax.inject.Singleton",
    ).all { fqn -> resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn)) != null }

    /** Koin is optional: the module is only generated when koin-core resolves. */
    private fun hasKoin(resolver: Resolver): Boolean =
        resolver.getClassDeclarationByName(
            resolver.getKSNameFromString("org.koin.core.module.Module"),
        ) != null

    /**
     * The abstract, no-argument DAO accessors declared directly on a database.
     * Room accessors fit exactly; helper functions that merely return a DAO are
     * left alone.
     */
    private fun daoAccessors(
        database: KSClassDeclaration,
        wrappedDaos: Set<String>,
        hiltDaos: Set<String>,
        koinDaos: Set<String>,
    ): List<DaoFacade.Accessor> = database.declarations
        .filterIsInstance<KSFunctionDeclaration>()
        .filter { fn ->
            Modifier.ABSTRACT in fn.modifiers &&
                fn.parameters.isEmpty() &&
                fn.typeParameters.isEmpty()
        }
        .mapNotNull { fn ->
            val returnType = fn.returnType?.resolve() ?: return@mapNotNull null
            if (returnType.isError || returnType.isMarkedNullable) return@mapNotNull null
            val dao = returnType.declaration as? KSClassDeclaration ?: return@mapNotNull null
            if (Ks.annotation(dao, Ks.ROOM_DAO) == null) return@mapNotNull null
            val fqn = dao.qualifiedName?.asString() ?: return@mapNotNull null
            DaoFacade.Accessor(
                name = fn.simpleName.asString(),
                daoType = returnType.toTypeName(),
                daoFqn = fqn,
                wrapped = fqn in wrappedDaos,
                internalDao = Modifier.INTERNAL in dao.modifiers,
                hilt = fqn in hiltDaos &&
                    Modifier.INTERNAL !in dao.modifiers &&
                    Modifier.INTERNAL !in database.modifiers,
                koin = fqn in koinDaos &&
                    Modifier.INTERNAL !in dao.modifiers &&
                    Modifier.INTERNAL !in database.modifiers,
            )
        }
        .toList()

    /** Best-effort source-file label for a symbol carrying a diagnostic. */
    private fun fileLabelOf(symbol: Any): String? = when (symbol) {
        is com.google.devtools.ksp.symbol.KSDeclaration -> symbol.containingFile?.fileName
        is com.google.devtools.ksp.symbol.KSFile -> symbol.fileName
        else -> null
    }

    /** Room shape detection by qualified name only — no Room dependency anywhere. */
    private fun roomKindOf(fn: KSFunctionDeclaration): String? = when {
        Ks.annotation(fn, Ks.ROOM_INSERT) != null -> "insert"
        Ks.annotation(fn, Ks.ROOM_UPSERT) != null -> "upsert"
        Ks.annotation(fn, Ks.ROOM_UPDATE) != null -> "update"
        Ks.annotation(fn, Ks.ROOM_DELETE) != null -> "delete"
        Ks.annotation(fn, Ks.ROOM_QUERY) != null -> "query"
        Ks.annotation(fn, Ks.ROOM_RAW_QUERY) != null -> "rawquery"
        Ks.annotation(fn, Ks.ROOM_TRANSACTION) != null -> "transaction"
        else -> null
    }

    private fun nodeOf(symbol: Any): com.google.devtools.ksp.symbol.KSNode? =
        symbol as? com.google.devtools.ksp.symbol.KSNode
}
