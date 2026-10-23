package io.ilogger.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidLifecycleHooksTest {

    private class TestOwner : LifecycleOwner {
        override val lifecycle: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
    }

    @Test
    fun `flushes when the process goes to the background`() {
        val owner = TestOwner()
        var flushes = 0
        val hooks = hooks(owner, flush = { flushes++ })

        hooks.attach()
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertEquals(1, flushes)
        hooks.close()
    }

    @Test
    fun `stop observing after close`() {
        val owner = TestOwner()
        var flushes = 0
        val hooks = hooks(owner, flush = { flushes++ })
        hooks.attach()
        hooks.close()

        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertEquals(0, flushes)
    }

    @Test
    fun `attach is idempotent and reports flush failures`() {
        val owner = TestOwner()
        var errors = 0
        var flushes = 0
        val hooks = hooks(owner, flush = { flushes++; throw IllegalStateException("disk full") }) { errors++ }

        hooks.attach()
        hooks.attach()
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

        assertEquals(1, flushes)
        assertEquals(1, errors)
        hooks.close()
    }

    private fun hooks(
        owner: TestOwner,
        flush: () -> Unit,
        onError: (Throwable) -> Unit = {},
    ): AndroidLifecycleHooks = AndroidLifecycleHooks(
        lifecycle = owner.lifecycle,
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
        flush = flush,
        onFlushError = onError,
    )
}
