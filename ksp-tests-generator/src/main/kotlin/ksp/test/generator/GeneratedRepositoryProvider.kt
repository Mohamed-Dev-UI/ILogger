package ksp.test.generator

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated

/**
 * Test-only processor that generates an `@Log` interface in round 1, so the
 * ILogger processor must instrument it in round 2. Gated on a marker source
 * file: test compilation sees the marker only as a classpath symbol, so the
 * interface is generated exactly once (main compilation).
 */
public class GeneratedRepositoryProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        GeneratedRepositoryProcessor(environment)
}

private class GeneratedRepositoryProcessor(
    private val environment: SymbolProcessorEnvironment,
) : SymbolProcessor {

    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        val inputs = resolver.getAllFiles().toList()
        if (inputs.none { file -> file.fileName == "SecondProcessorMarker.kt" }) return emptyList()
        generated = true
        environment.codeGenerator.createNewFile(
            Dependencies(aggregating = false, *inputs.toTypedArray()),
            packageName = "ksp.test.generated",
            fileName = "GeneratedRepository",
        ).bufferedWriter().use { writer ->
            writer.write(SOURCE)
        }
        return emptyList()
    }

    private companion object {
        const val SOURCE = """
            package ksp.test.generated

            import io.ilogger.annotations.Log

            public interface GeneratedRepository {
                @Log(action = "generated.operation", entityId = "value")
                public suspend fun run(value: String): String
            }
        """
    }
}
