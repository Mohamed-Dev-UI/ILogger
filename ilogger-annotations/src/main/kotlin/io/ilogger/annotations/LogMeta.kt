package io.ilogger.annotations

import kotlin.reflect.KClass

/**
 * Selects one piece of metadata to include in a [Log] event, from a
 * compile-time-validated source expression. See [Log] for the expression
 * grammar (`result`, `result.prop`, `paramName.prop`, `'literal'`,
 * `actor.*`, `context.*`).
 *
 * ILogger intentionally captures **only selected values**: passing an entity
 * to an annotated function does not serialize it, and unselected properties
 * never enter an event. This bounds allocations, storage and privacy risk.
 *
 * Values must be loggable primitives (numbers, booleans, strings, enums or
 * null); anything else is a compile error unless an [extractor] is provided.
 *
 * ```kotlin
 * @Log(
 *     action = TaskActions.CREATED,
 *     metadata = [
 *         LogMeta("task.projectId"),
 *         LogMeta("task.priority"),
 *         LogMeta("'v2'", name = "pipeline"),   // constant
 *     ],
 * )
 * ```
 *
 * @param value Source expression resolved and type-checked at compile time.
 * @param name Metadata key in the event. Defaults to the last segment of the
 *   source expression (`"task.projectId"` → `"projectId"`); constants default
 *   to `"value"`.
 * @param sensitive Required policy when the selected value (or any of its
 *   enclosing types) is marked [LogSensitive]. The default [SensitiveHandling.DENY]
 *   makes such references a compile error so sensitive data can never be
 *   included by omission.
 * @param extractor Optional class implementing
 *   `io.ilogger.core.LogValueExtractor`. When present, the extractor converts
 *   the selected value into loggable primitives. Generated wrappers resolve it
 *   once through `ILogger.extractors` — a factory registered with
 *   `ILogger.create { extractor(...) }` wins; otherwise the class is
 *   constructed once via its public no-argument constructor. Its output is
 *   validated: non-loggable values and thrown exceptions omit the entry.
 * @param adapter Optional class implementing `io.ilogger.core.ValueAdapter`
 *   with a no-argument constructor. When present, the adapter's
 *   `render(value)` result becomes the metadata value. Mutually exclusive
 *   with [extractor]; the class is instantiated once per generated wrapper.
 * @param inject Requires [extractor] to be registered on the logger before its
 *   first use; generated code does not construct a fallback. Use it when the
 *   extractor's constructor needs application services, and register the
 *   factory in `ILogger.create { extractor(...) }`. Only meaningful together
 *   with [extractor].
 */
public annotation class LogMeta(
    public val value: String,
    public val name: String = "",
    public val sensitive: SensitiveHandling = SensitiveHandling.DENY,
    public val extractor: KClass<*> = Nothing::class,
    public val adapter: KClass<*> = Nothing::class,
    public val inject: Boolean = false,
)
