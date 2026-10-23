package diagwarn

import io.ilogger.annotations.Log
import io.ilogger.annotations.LogActions
import io.ilogger.annotations.LogConfig
import io.ilogger.annotations.LogDao
import io.ilogger.annotations.LogMeta
import io.ilogger.annotations.LogSensitive
import io.ilogger.annotations.SensitiveHandling

data class Account(
    val id: Long,
    @LogSensitive val token: String,
    val passwordHint: String,
)

/**
 * Declaring one code twice in the same registry is noise; the generated
 * registry deduplicates it but the contract is ambiguous for tooling.
 */
// EXPECTED: is declared more than once in @LogActions
@LogActions("order.created", "order.created", "audit.export")
object MessyRegistry

/**
 * Two registries owning the same code force routing to guess the owner.
 */
// EXPECTED: is declared by both 'MessyRegistry' and 'DuplicateRegistry'
@LogActions("audit.export")
object DuplicateRegistry

/**
 * Intentional shared contracts are silenced at the declaration site.
 */
@Suppress("ILG062")
@LogActions("shared.contract", "shared.contract")
object SuppressedRegistry

/**
 * Once the module has a registry, a typo in @Log(action) is a warning
 * instead of a silently unregistered event.
 */
interface UndeclaredAction {
    // EXPECTED: is not declared in any @LogActions registry
    @Log(action = "typo.creted", entityId = "'fixed'")
    suspend fun touch()
}

/**
 * A single-segment code never routes by domain.
 */
// EXPECTED: has a single segment
@LogActions("shortcut")
object SingleSegmentRegistry

/**
 * A DAO opted into logging but never exposed by a database would produce no
 * wrapper, so the opt-in is reported.
 */
// EXPECTED: is not exposed by any @Database accessor
@LogDao
interface OrphanDao {
    @Log(action = "order.read", entity = Account::class)
    suspend fun read(id: Long): Account?
}

/**
 * `enabled = false` is the explicit opt-out: no adapter opt-in and no
 * unused-DAO warning.
 */
@LogDao(enabled = false)
interface DisabledDao {
    @Log(action = "order.disabled", entity = Account::class)
    suspend fun read(id: Long): Account?
}

/**
 * An explicit actor selecting a sensitive attribute is not rejected, but the
 * container MASK policy silently changes what the actor column records.
 */
@LogConfig(sensitive = SensitiveHandling.MASK)
interface MaskedActor {
    // EXPECTED: Actor override 'account.token' selects sensitive values
    @Log(
        action = "order.audited",
        entity = Account::class,
        actor = "account.token",
        category = "audit",
    )
    suspend fun audit(account: Account): Long
}

/**
 * ILG035 is suppressed module-wide (see build.gradle.kts); the selection
 * itself is valid.
 */
interface SuspiciousSelection {
    @Log(
        action = "order.inspected",
        entity = Account::class,
        metadata = [LogMeta("account.passwordHint", name = "hint")],
    )
    suspend fun inspect(account: Account): Long
}

/**
 * Fully compliant operation: declared action, no sensitive selections and a
 * non-sensitive actor source. It must contribute zero diagnostics.
 */
@LogActions("positive.clean")
object CleanRegistry

interface CleanOperation {
    @Log(
        action = "positive.clean",
        entity = Account::class,
        actor = "account.id",
        metadata = [LogMeta("account.id", name = "ownerId")],
    )
    suspend fun clean(account: Account): Long
}
