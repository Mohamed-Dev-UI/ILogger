package io.ilogger.android

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.ilogger.core.ILogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Flushes the logger when the process goes to the background.
 *
 * Buffered pipelines keep events in memory between batches; a process killed
 * in the background would lose the tail. Android reports this transition
 * through [ProcessLifecycleOwner], and this observer turns `ON_STOP` into one
 * `flush()` call on an IO dispatcher so the main thread is never blocked.
 *
 * The hook owns its coroutine scope; [close] detaches the observer and cancels
 * pending flushes. Flush failures are reported to [onFlushError] and never
 * crash the process.
 *
 * [attach] and [close] must run on the main thread, as `Lifecycle` requires;
 * attach the hook from `Application.onCreate`.
 */
public class AndroidLifecycleHooks internal constructor(
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val flush: () -> Unit,
    private val onFlushError: (Throwable) -> Unit,
) : DefaultLifecycleObserver, AutoCloseable {

    public constructor(
        logger: ILogger,
        lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
    ) : this(
        lifecycle = lifecycle,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        flush = logger::flush,
        onFlushError = {},
    )

    private val lock = Any()
    private var attached = false

    /** Starts observing; repeated calls are ignored. */
    public fun attach(): AndroidLifecycleHooks {
        synchronized(lock) {
            if (!attached) {
                lifecycle.addObserver(this)
                attached = true
            }
        }
        return this
    }

    override fun onStop(owner: LifecycleOwner) {
        scope.launch {
            runCatching(flush).onFailure { error -> runCatching { onFlushError(error) } }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (attached) {
                lifecycle.removeObserver(this)
                attached = false
            }
        }
        scope.cancel()
    }
}
