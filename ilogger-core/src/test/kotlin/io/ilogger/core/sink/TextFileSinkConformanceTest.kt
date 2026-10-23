package io.ilogger.core.sink

import io.ilogger.core.LogSink
import io.ilogger.testing.LogSinkConformance
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Runs the published sink contract against the file sink. */
class TextFileSinkConformanceTest : LogSinkConformance() {

    @get:Rule
    public val folder: TemporaryFolder = TemporaryFolder()

    private var file: Path? = null

    override fun createSink(): LogSink {
        val path = folder.root.toPath().resolve("conformance.log")
        file = path
        return TextFileSink(path, codec = EventCodec { it.id })
    }

    override fun deliveredIds(sink: LogSink): List<String> {
        val path = checkNotNull(file) { "createSink has not run" }
        return if (Files.exists(path)) Files.readAllLines(path).filter { it.isNotEmpty() } else emptyList()
    }
}
