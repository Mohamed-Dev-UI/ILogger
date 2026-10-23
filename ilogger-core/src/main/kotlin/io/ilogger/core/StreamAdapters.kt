package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import io.ilogger.annotations.OnCancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * Stream instrumentation adapters. Unlike generated KSP wrappers (which wrap
 * one annotated operation), these wrap arbitrary streams at the consumption
 * site with explicit lifecycle events:
 *
 * - one [Outcome.START] event when the stream is subscribed/iterated,
 * - one [Outcome.SUCCESS] event on normal completion,
 * - one [Outcome.FAILURE] event when the stream errors — the original error is
 *   never swallowed or replaced by a logging failure,
 * - cancellation follows [onCancellation] (skipped by default, mirroring the
 *   generated wrappers).
 *
 * Events carry the [action], [level], [entity], [entityId], [metadata],
 * [source] and [category] supplied here; per-value events are out of scope by
 * design (see the generated-code guide).
 */

/**
 * Wraps a [Flow] so collecting it emits subscription and terminal events.
 * The returned flow is cold and preserves the upstream's context semantics;
 * every subscription produces its own pair of events.
 */
public fun <T> Flow<T>.loggedFlow(
    logger: ILogger,
    action: String,
    actionVersion: Int = 1,
    level: LogLevel = LogLevel.INFO,
    entity: String? = null,
    entityId: String? = null,
    source: String? = null,
    category: String? = null,
    metadata: Map<String, Any?> = emptyMap(),
    dispatch: DispatchMode = DispatchMode.DEFAULT,
    onCancellation: OnCancellation = OnCancellation.SKIP,
): Flow<T> {
    if (!logger.enabled) return this
    return onStart {
        logger.record(
            action = action,
            level = level,
            entity = entity,
            entityId = entityId,
            outcome = Outcome.START,
            metadata = metadata,
            source = source,
            category = category,
            actionVersion = actionVersion,
            dispatch = dispatch,
        )
    }.onCompletion { cause ->
        when {
            cause == null -> logger.record(
                action = action,
                level = level,
                entity = entity,
                entityId = entityId,
                outcome = Outcome.SUCCESS,
                metadata = metadata,
                source = source,
                category = category,
                actionVersion = actionVersion,
                dispatch = dispatch,
            )
            cause is CancellationException -> {
                // A cancelled coroutine must never suspend on logging.
                if (onCancellation == OnCancellation.LOG) {
                    logger.recordNow(
                        action = action,
                        level = level,
                        entity = entity,
                        entityId = entityId,
                        outcome = Outcome.FAILURE,
                        failure = cause,
                        metadata = metadata,
                        source = source,
                        category = category,
                        actionVersion = actionVersion,
                        dispatch = DispatchMode.ASYNC,
                    )
                }
            }
            else -> try {
                logger.record(
                    action = action,
                    level = level,
                    entity = entity,
                    entityId = entityId,
                    outcome = Outcome.FAILURE,
                    failure = cause,
                    metadata = metadata,
                    source = source,
                    category = category,
                    actionVersion = actionVersion,
                    dispatch = dispatch,
                )
            } catch (dispatchError: Throwable) {
                cause.addSuppressed(dispatchError)
                throw cause
            }
        }
    }
}

/**
 * Consuming view of a [ReceiveChannel] with subscription and terminal events.
 * Channels are consumed once: the returned [Flow] closes over this channel's
 * consumption; a second collection sees an already-closed channel. The
 * adapter does not cancel the channel on its own.
 */
public fun <T> ReceiveChannel<T>.loggedChannel(
    logger: ILogger,
    action: String,
    actionVersion: Int = 1,
    level: LogLevel = LogLevel.INFO,
    entity: String? = null,
    entityId: String? = null,
    source: String? = null,
    category: String? = null,
    metadata: Map<String, Any?> = emptyMap(),
    dispatch: DispatchMode = DispatchMode.DEFAULT,
    onCancellation: OnCancellation = OnCancellation.SKIP,
): Flow<T> = consumeAsFlow().loggedFlow(
    logger = logger,
    action = action,
    actionVersion = actionVersion,
    level = level,
    entity = entity,
    entityId = entityId,
    source = source,
    category = category,
    metadata = metadata,
    dispatch = dispatch,
    onCancellation = onCancellation,
)

/**
 * Wraps a cold [Sequence] with subscription and terminal events. Events use
 * [ILogger.recordNow] (sequences are synchronous): START when the first
 * iterator is created, SUCCESS on exhaustion, FAILURE on iteration error.
 */
public fun <T> Sequence<T>.loggedSequence(
    logger: ILogger,
    action: String,
    actionVersion: Int = 1,
    level: LogLevel = LogLevel.INFO,
    entity: String? = null,
    entityId: String? = null,
    source: String? = null,
    category: String? = null,
    metadata: Map<String, Any?> = emptyMap(),
    dispatch: DispatchMode = DispatchMode.ASYNC,
    onCancellation: OnCancellation = OnCancellation.SKIP,
): Sequence<T> {
    if (!logger.enabled) return this
    return Sequence {
        logger.recordNow(
            action = action,
            level = level,
            entity = entity,
            entityId = entityId,
            outcome = Outcome.START,
            metadata = metadata,
            source = source,
            category = category,
            actionVersion = actionVersion,
            dispatch = dispatch,
        )
        val iterator = this@loggedSequence.iterator()
        var finished = false
        object : Iterator<T> {
            override fun hasNext(): Boolean {
                val more = try {
                    iterator.hasNext()
                } catch (error: Throwable) {
                    recordFailure(error)
                    throw error
                }
                if (!more && !finished) {
                    finished = true
                    logger.recordNow(
                        action = action,
                        level = level,
                        entity = entity,
                        entityId = entityId,
                        outcome = Outcome.SUCCESS,
                        metadata = metadata,
                        source = source,
                        category = category,
                        actionVersion = actionVersion,
                        dispatch = dispatch,
                    )
                }
                return more
            }

            override fun next(): T = try {
                iterator.next()
            } catch (error: Throwable) {
                // Exhaustion misuse (NoSuchElementException after a successful
                // end) is a consumer error, not a stream failure: `finished`
                // already recorded SUCCESS.
                if (!finished) {
                    finished = true
                    recordFailure(error)
                }
                throw error
            }

            private fun recordFailure(error: Throwable) {
                if (error is CancellationException) {
                    if (onCancellation == OnCancellation.LOG) {
                        logger.recordNow(
                            action = action,
                            level = level,
                            entity = entity,
                            entityId = entityId,
                            outcome = Outcome.FAILURE,
                            failure = error,
                            metadata = metadata,
                            source = source,
                            category = category,
                            actionVersion = actionVersion,
                            dispatch = DispatchMode.ASYNC,
                        )
                    }
                } else {
                    try {
                        logger.recordNow(
                            action = action,
                            level = level,
                            entity = entity,
                            entityId = entityId,
                            outcome = Outcome.FAILURE,
                            failure = error,
                            metadata = metadata,
                            source = source,
                            category = category,
                            actionVersion = actionVersion,
                            dispatch = dispatch,
                        )
                    } catch (dispatchError: Throwable) {
                        error.addSuppressed(dispatchError)
                    }
                }
            }
        }
    }
}
