package io.ilogger.android

import android.content.Context
import io.ilogger.core.ILogger
import io.ilogger.core.ILoggerBuilder
import io.ilogger.core.LogSink
import io.ilogger.core.sink.TextFileSink
import java.io.File

/**
 * One-line Android setup:
 *
 * ```kotlin
 * class App : Application() {
 *     lateinit var logging: AndroidLogger
 *     override fun onCreate() {
 *         logging = ILoggerAndroid.install(this) {
 *             logcat(category = "diagnostics")
 *             file(name = "audit.jsonl", category = "audit")
 *             logger { strictMode = StrictMode.WARN }
 *         }
 *     }
 * }
 * ```
 *
 * The returned [AndroidLogger] owns the logger, lifecycle observer and crash
 * handler. [AndroidLogger.close] flushes/closes the logger and restores the
 * previous uncaught-exception handler.
 *
 * Defaults (all overridable through the options block):
 * - the logger is installed as [ILogger.default];
 * - a catch-all [LogcatSink] writes every event to Logcat;
 * - a [CrashBuffer] is bound catch-all and [CrashHandoff] is installed;
 * - [AndroidLifecycleHooks] flushes on process `ON_STOP`;
 * - [AndroidActorProvider] supplies the session/user actor.
 *
 * Call from `Application.onCreate` on the main thread: the lifecycle observer
 * is attached to `ProcessLifecycleOwner`.
 */
public object ILoggerAndroid {

    public fun install(
        context: Context,
        configure: AndroidLoggerOptions.() -> Unit = {},
    ): AndroidLogger {
        val options = AndroidLoggerOptions(context.applicationContext).apply(configure)
        val logger = ILogger.create { options.applyTo(this) }
        if (options.setAsDefault) ILogger.setDefault(logger)

        val handoff = if (options.crashHandoff) options.createCrashHandoff().install() else null
        val hooks = if (options.flushOnStop) AndroidLifecycleHooks(logger).attach() else null

        return AndroidLogger(
            logger = logger,
            actors = options.actors,
            crashBuffer = options.crashBuffer,
            crashHandoff = handoff,
            lifecycleHooks = hooks,
            filePath = options.filePath,
            setAsDefault = options.setAsDefault,
        )
    }
}

/**
 * DSL for [ILoggerAndroid.install]. Sinks declared here are registered before
 * the user's [logger] block, so the block can override builder fields (for
 * example a custom `actorProvider` or `dispatch`) but cannot accidentally
 * remove the Android sinks without explicitly doing so.
 */
public class AndroidLoggerOptions internal constructor(private val context: Context) {

    /** Session-aware actor provider; assign it to the builder yourself to opt out. */
    public val actors: AndroidActorProvider = AndroidActorProvider(context)

    /** Recent-event ring written on an uncaught exception. */
    public val crashBuffer: CrashBuffer = CrashBuffer()

    /** Install the process-wide crash handler. */
    public var crashHandoff: Boolean = true

    /** Flush when the process goes to the background. */
    public var flushOnStop: Boolean = true

    /** Publish the logger as `ILogger.default`. */
    public var setAsDefault: Boolean = true

    /** Crash report directory. */
    public var crashDirectory: File = File(context.filesDir, "ilogger/crash")

    /** Catch-all Logcat sink installed by default; `null` disables it. */
    public var logcatSink: LogcatSink? = LogcatSink()

    public var logcatCategory: String? = null

    /** Optional JSONL file sink. */
    public var fileSink: LogSink? = null

    public var fileCategory: String? = null

    /** Absolute path of [fileSink], when one was created. */
    public var filePath: String? = null

    private val blocks = mutableListOf<ILoggerBuilder.() -> Unit>()

    /** Removes the default Logcat sink. */
    public fun disableLogcat() {
        logcatSink = null
    }

    /** Replaces the Logcat sink with one using [tag], optionally routed to a category. */
    public fun logcat(tag: String = LogcatSink.DEFAULT_TAG, category: String? = null) {
        logcatSink = LogcatSink(tag = tag)
        logcatCategory = category
    }

    /** Adds a StrictMode-safe JSONL file sink under `filesDir/ilogger`. */
    public fun file(
        name: String = "ilogger.jsonl",
        directory: String = "ilogger",
        category: String? = null,
        rotation: TextFileSink.Rotation? = null,
        fsyncEveryWrite: Boolean = false,
    ) {
        val sink = AndroidFileSink.create(
            context = context,
            name = name,
            directory = directory,
            rotation = rotation,
            fsyncEveryWrite = fsyncEveryWrite,
        )
        fileSink = sink
        fileCategory = category
        filePath = sink.path
    }

    /** Raw access to the underlying builder (dispatch, strict mode, sampling, custom sinks). */
    public fun logger(configure: ILoggerBuilder.() -> Unit) {
        blocks += configure
    }

    internal fun applyTo(builder: ILoggerBuilder) {
        builder.actorProvider = actors
        logcatSink?.let { sink -> builder.sink(sink, logcatCategory) }
        fileSink?.let { sink -> builder.sink(sink, fileCategory) }
        if (crashHandoff) builder.sink(crashBuffer)
        blocks.forEach { block -> block(builder) }
    }

    internal fun createCrashHandoff(): CrashHandoff =
        CrashHandoff(buffer = crashBuffer, directory = crashDirectory)
}

/** Handle returned by [ILoggerAndroid.install]; close it to release every Android hook. */
public class AndroidLogger internal constructor(
    public val logger: ILogger,
    public val actors: AndroidActorProvider,
    public val crashBuffer: CrashBuffer,
    public val crashHandoff: CrashHandoff?,
    public val filePath: String?,
    private val lifecycleHooks: AndroidLifecycleHooks?,
    private val setAsDefault: Boolean,
) : AutoCloseable {

    override fun close() {
        lifecycleHooks?.close()
        crashHandoff?.uninstall()
        logger.close()
        if (setAsDefault && ILogger.default === logger) {
            ILogger.setDefault(null)
        }
    }
}
