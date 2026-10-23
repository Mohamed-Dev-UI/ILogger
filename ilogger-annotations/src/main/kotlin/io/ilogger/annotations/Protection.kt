package io.ilogger.annotations

/**
 * Marks a class, property, parameter, receiver or return type as *sensitive*:
 * it may only appear in events after an explicit, chosen
 * [io.ilogger.annotations.SensitiveHandling] policy ([MASK], [HASH] or
 * [OMIT]) at the selection site, or under a container-wide
 * [LogConfig] policy.
 *
 * Applying this annotation never changes runtime behavior by itself — it is a
 * compile-time contract that ILogger's processors enforce everywhere values
 * can be selected (metadata, entity ids, change tracking, actor overrides).
 *
 * ```kotlin
 * data class User(
 *     val id: Long,
 *     @LogSensitive val email: String,
 * )
 *
 * fun issue(@LogSensitive token: String): @LogSensitive Session
 * ```
 *
 * A parameter annotation (`@LogSensitive token: String`) and a type-use
 * annotation (`token: @LogSensitive String`, `fun load(): @LogSensitive User`,
 * `@receiver:LogSensitive`) mark the value at the root of a selection chain;
 * a property annotation marks it at any depth.
 *
 * ILogger additionally refuses to include values from the following
 * properties by *name* even without this annotation — as a defense in depth
 * for untyped/dynamic metadata: `password`, `passwd`, `pwd`, `token`,
 * `secret`, `apiKey`, `privateKey`, `credential(s)`, `sessionKey`, `refreshToken`,
 * `accessToken`, `authorization`, `cookie`.
 *
 * @param reason Free-form justification, shown in compile diagnostics and
 *   documentation tooling.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class LogSensitive(public val reason: String = "sensitive value")

/**
 * Marks a class, property, parameter, receiver or return type as *excluded*
 * from logging: ILogger's processors reject every reference to it in [Log]
 * metadata, entity id and change selection, regardless of policy. Use for
 * values that must never reach any log sink, not even masked.
 *
 * ```kotlin
 * fun authenticate(@LogExclude rawPassword: String): Boolean
 * ```
 *
 * @param reason Free-form justification, shown in compile diagnostics.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class LogExclude(public val reason: String = "excluded from logging")

/**
 * Marks the stable identifier property of an entity class so entity-id
 * inference can use it instead of the `id` naming convention. This is the
 * application-side counterpart of framework integrations (e.g. Room's
 * `@PrimaryKey` is honored automatically).
 *
 * ```kotlin
 * class Session {
 *     @LogEntityId val sessionKey: UUID,
 *     ...
 * }
 * ```
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class LogEntityId
