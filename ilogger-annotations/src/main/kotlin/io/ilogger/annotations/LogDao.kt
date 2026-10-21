package io.ilogger.annotations

/**
 * Opts an interface into automatic wrapping through a generated database
 * facade.
 *
 * The processor generates `<Database>_ILogged` plus
 * `fun <Database>.logged(logger: ILogger): <Database>_ILogged`. Every DAO
 * accessor on that facade returns the annotated DAO already wrapped by its
 * generated `.logged(logger)` adapter, so call sites never repeat the wrapper:
 *
 * ```kotlin
 * @LogDao
 * @Dao
 * interface TaskDao {
 *     @Log(action = TaskActions.CREATED)
 *     @Insert
 *     suspend fun insert(task: TaskRow): Long
 * }
 *
 * @Database(entities = [TaskRow::class], version = 1)
 * abstract class AppDatabase : RoomDatabase() {
 *     abstract fun taskDao(): TaskDao
 * }
 *
 * val daos = db.logged(logger)      // generated facade
 * daos.taskDao().insert(task)       // logged automatically
 * ```
 *
 * This annotation only changes **how the adapter is obtained**. Every logged
 * method still carries [Log]: actions and entity semantics are never derived
 * from method names. The explicit `taskDao().logged(logger)` form remains
 * available and unchanged.
 *
 * `@LogDao(enabled = false)` keeps the adapter generation (so explicit
 * `.logged(...)` still works) but makes the database facade return the raw DAO
 * for that interface — useful for DAOs whose calls must not be audited.
 *
 * ## Optional Hilt binding
 *
 * `@LogDao(hilt = true)` additionally generates
 * `<Database>_ILogged_HiltModule`, which provides the already-wrapped DAO from
 * the singleton graph:
 *
 * ```kotlin
 * @LogDao(hilt = true)
 * @Dao interface TaskDao { ... }
 *
 * @Inject lateinit var taskDao: TaskDao   // already instrumented
 * ```
 *
 * Generation is skipped (with a compile-time diagnostic) when Hilt is not on
 * the consumer's classpath; the core library never depends on Hilt. The
 * database and DAO must be public for the generated module, and the same DAO
 * exposed by two `@Database` classes is rejected as a duplicate binding.
 *
 * ## Optional Koin binding
 *
 * `@LogDao(koin = true)` generates `<Database>_ILogged_KoinModule` with a
 * `Module` property, so Koin resolves the wrapped DAO directly:
 *
 * ```kotlin
 * @LogDao(koin = true)
 * @Dao interface TaskDao { ... }
 *
 * startKoin {
 *     modules(AppDatabase_ILogged_KoinModule.module, appModule)
 * }
 * ```
 *
 * The definitions resolve `AppDatabase` and `ILogger` from Koin, so both must
 * be registered in the application graph. As with Hilt, generation is skipped
 * when Koin is absent from the compilation and duplicate bindings across
 * databases are rejected.
 *
 * The annotation is read at compile time by `ilogger-ksp`; it has no runtime
 * effect and no dependency on Room (the processor recognizes `@Dao` and
 * `@Database` by qualified name).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
public annotation class LogDao(
    /**
     * Whether the generated database facade wraps this DAO. When `false`, the
     * facade returns the raw Room DAO; explicit `.logged(logger)` is unaffected.
     */
    public val enabled: Boolean = true,

    /**
     * Generates a Hilt `@Module` binding this DAO as `db.logged(logger).taskDao()`.
     * Requires [enabled] and Hilt on the consumer classpath; default `false`.
     */
    public val hilt: Boolean = false,

    /**
     * Generates a Koin module binding this DAO as `db.logged(logger).taskDao()`.
     * Requires [enabled] and Koin on the consumer classpath; default `false`.
     */
    public val koin: Boolean = false,
)
