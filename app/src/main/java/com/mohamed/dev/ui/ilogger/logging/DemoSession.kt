package com.mohamed.dev.ui.ilogger.logging

import io.ilogger.core.Actor
import io.ilogger.core.LogContext
import io.ilogger.core.withLogContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** The actors a developer can switch between in the demo. */
enum class DemoCast(val label: String, val detail: String) {
    USER("Grace · user", "Authenticated human, actor attributes include her team"),
    SYSTEM("System", "The application itself, no actor id"),
    WORKER("Nightly worker", "A scheduled job with an id"),
    AUTOMATION("Auto-archive", "A rule-driven automation"),
    SERVICE("Mobile BFF", "A machine caller / service account"),
    ANONYMOUS("Anonymous", "Unauthenticated caller — still records who acted"),
    ;

    fun actor(): Actor = when (this) {
        USER -> Actor.user("u-42", name = "Grace", attributes = mapOf("team" to "audit"))
        SYSTEM -> Actor.system(attributes = mapOf("runtime" to "android"))
        WORKER -> Actor.worker(id = "job-7", name = "Nightly export")
        AUTOMATION -> Actor.automation(id = "rule-3", name = "Auto-archive")
        SERVICE -> Actor.service(id = "svc-mobile", name = "Mobile BFF")
        ANONYMOUS -> Actor.anonymous()
    }
}

/**
 * The demo's "session": current actor, tenant attribute, correlation policy and
 * the `suppressSources` toggle. Every UI action runs through [run], so the
 * ambient context the library resolves is explicit and visible in the UI.
 *
 * This class is intentionally application-owned: ILogger never assumes an auth
 * model and never reads thread-locals on its own.
 */
@Singleton
class DemoSession @Inject constructor() {

    private val _cast = MutableStateFlow(DemoCast.USER)
    val cast: StateFlow<DemoCast> = _cast.asStateFlow()

    private val _tenant = MutableStateFlow(TENANTS.first())
    val tenant: StateFlow<String> = _tenant.asStateFlow()

    private val _correlationEnabled = MutableStateFlow(true)
    val correlationEnabled: StateFlow<Boolean> = _correlationEnabled.asStateFlow()

    private val _suppressStorage = MutableStateFlow(true)
    val suppressStorage: StateFlow<Boolean> = _suppressStorage.asStateFlow()

    private val _lastCorrelationId = MutableStateFlow<String?>(null)
    val lastCorrelationId: StateFlow<String?> = _lastCorrelationId.asStateFlow()

    private val operationCounter = AtomicInteger(0)

    fun selectCast(cast: DemoCast) {
        _cast.value = cast
    }

    fun selectTenant(tenant: String) {
        if (tenant in TENANTS) _tenant.value = tenant
    }

    fun setCorrelationEnabled(enabled: Boolean) {
        _correlationEnabled.value = enabled
        if (!enabled) _lastCorrelationId.value = null
    }

    fun setSuppressStorage(enabled: Boolean) {
        _suppressStorage.value = enabled
    }

    /**
     * Runs one logical UI operation with this session's actor, tenant attribute
     * and a fresh correlation id. Nested scopes merge through `LogContext.plus`.
     */
    suspend fun <R> run(block: suspend CoroutineScope.() -> R): R {
        val correlationId = if (_correlationEnabled.value) {
            "op-${operationCounter.incrementAndGet().toString().padStart(3, '0')}"
        } else {
            null
        }
        _lastCorrelationId.value = correlationId
        return withLogContext(
            LogContext(
                actor = _cast.value.actor(),
                correlationId = correlationId,
                attributes = attributes(),
            ),
            block,
        )
    }

    /** Context for the non-suspend `recordNow` path: it needs the thread-local bridge. */
    fun threadContext(): LogContext = LogContext(actor = _cast.value.actor(), attributes = attributes())

    /**
     * Wraps storage calls in `suppressSources` while the switch is on. The
     * domain event recorded by the repository wrapper stays visible; only the
     * inner DAO event is dropped before allocation.
     */
    suspend fun <R> storage(block: suspend () -> R): R {
        if (!_suppressStorage.value) return block()
        return withLogContext(LogContext(suppressSources = TASK_DAO_SOURCES)) { block() }
    }

    private fun attributes(): Map<String, String> = mapOf(
        "tenantId" to _tenant.value,
        "sessionId" to "android-demo",
    )

    companion object {
        val TENANTS: List<String> = listOf("acme", "globex", "toronto")

        /**
         * Exact `LogEvent.source` values produced by the generated TaskDao
         * wrapper. `suppressSources` matches exactly, never by prefix.
         */
        val TASK_DAO_SOURCES: Set<String> = setOf(
            "TaskDao.insert",
            "TaskDao.insertAll",
            "TaskDao.rename",
            "TaskDao.complete",
            "TaskDao.archive",
            "TaskDao.assign",
            "TaskDao.delete",
            "TaskDao.byId",
        )
    }
}
