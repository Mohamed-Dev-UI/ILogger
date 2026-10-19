package io.ilogger.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
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
        if (annotated.isEmpty()) return emptyList()

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
        return emptyList()
    }

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
