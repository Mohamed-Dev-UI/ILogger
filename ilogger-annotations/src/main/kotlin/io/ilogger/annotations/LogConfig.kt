package io.ilogger.annotations

/**
 * Class- or interface-level defaults for [Log] on the annotated container.
 *
 * A [Log] field left at its own default (`level = AUDIT`, `category = ""`,
 * `dispatch = DEFAULT`, `sampling = -1.0`) inherits the corresponding
 * [LogConfig] value; explicit `@Log` values always win. A method that must
 * keep `AUDIT` under a non-AUDIT class config (or otherwise opt out of
 * inheritance) sets `@Log(useClassDefaults = false)`.
 *
 * ```kotlin
 * @LogConfig(category = "billing", level = LogLevel.INFO)
 * interface BillingRepository {
 *     @Log(action = "billing.charged", metadata = [...])
 *     suspend fun charge(order: Order)      // INFO + category billing
 *
 *     @Log(action = "billing.audited", level = LogLevel.AUDIT, useClassDefaults = false)
 *     suspend fun audittrail(order: Order)  // explicitly AUDIT, still category-less
 * }
 * ```
 *
 * @param category Default category for events of this container; empty means
 *   the default route.
 * @param level Default level; `AUDIT` (the annotation default) provides no
 *   override, so methods keep their own explicit levels.
 * @param dispatch Default dispatch mode; `DEFAULT` provides no override.
 * @param sampling Default sampling rate; `-1.0` provides no override. Rates
 *   below 1.0 on audit-level events are rejected exactly like method-level
 *   sampling.
 * @param sensitive Default policy for `LogMeta` entries that select a
 *   [LogSensitive] value without an explicit policy: `MASK` or `HASH` turns
 *   the would-be compile error into that policy. `DENY` (default) keeps the
 *   error. [SensitiveHandling.OMIT] is not accepted here.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class LogConfig(
    public val category: String = "",
    public val level: LogLevel = LogLevel.AUDIT,
    public val dispatch: DispatchMode = DispatchMode.DEFAULT,
    public val sampling: Double = -1.0,
    public val sensitive: SensitiveHandling = SensitiveHandling.DENY,
)
