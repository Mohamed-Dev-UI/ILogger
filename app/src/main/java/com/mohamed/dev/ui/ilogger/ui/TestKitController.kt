package com.mohamed.dev.ui.ilogger.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class BenchCheck(
    val name: String,
    val passed: Boolean,
    val expected: String,
    val actual: String,
)

/**
 * Runs [BenchSuite] inside the app: INLINE dispatch, a fixed clock and
 * sequential ids make every assertion deterministic, no sleeps required.
 */
@Singleton
class TestKitController @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _checks = MutableStateFlow<List<BenchCheck>>(emptyList())
    val checks: StateFlow<List<BenchCheck>> = _checks.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastRunMillis = MutableStateFlow<Long?>(null)
    val lastRunMillis: StateFlow<Long?> = _lastRunMillis.asStateFlow()

    fun runChecks() {
        if (_running.value) return
        scope.launch {
            _running.value = true
            try {
                _checks.value = BenchSuite.run()
                _lastRunMillis.value = System.currentTimeMillis()
            } finally {
                _running.value = false
            }
        }
    }
}
