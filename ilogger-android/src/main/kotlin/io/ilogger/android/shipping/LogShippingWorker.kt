package io.ilogger.android.shipping

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional WorkManager worker that drains a [ShippingSpool] through a
 * [ShippingTransport].
 *
 * WorkManager is a **compile-only** dependency of `ilogger-android`: installs
 * that use shipping add `androidx.work:work-runtime-ktx` themselves and call
 * [configure] with a transport (typically a remote sink or a custom HTTP
 * client). Apps that never schedule the worker pay nothing.
 *
 * Delivery contract: one invocation drains up to ten bounded batches. A
 * transport returning `false` (or throwing) leaves the spool untouched and
 * triggers WorkManager's exponential backoff. Duplicate delivery is possible
 * after a crash between send and acknowledge — receivers must deduplicate by
 * event id, which [io.ilogger.core.LogEvent] guarantees is unique.
 */
public class LogShippingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val transport = configuredTransport ?: return Result.failure()
        val spool = ShippingSpool(spoolFile(applicationContext))
        val runner = ShippingRunner(spool, transport)
        repeat(MAX_BATCHES_PER_RUN) {
            when (runner.runOnce()) {
                ShippingRunner.Outcome.NOTHING -> return Result.success()
                ShippingRunner.Outcome.SENT -> Unit
                ShippingRunner.Outcome.FAILED -> return Result.retry()
            }
        }
        return if (spool.size() == 0) Result.success() else Result.retry()
    }

    public companion object {
        public const val UNIQUE_WORK_NAME: String = "ilogger-log-shipping"
        public const val SPOOL_DIRECTORY: String = "ilogger/ship"
        public const val SPOOL_FILE_NAME: String = "pending.jsonl"

        private const val MAX_BATCHES_PER_RUN = 10

        @Volatile
        private var configuredTransport: ShippingTransport? = null

        /**
         * Sets the transport used by every worker invocation. Call once at
         * startup, before [enqueue]. Passing `null` makes the worker fail
         * permanently instead of retrying an unconfigured pipeline.
         */
        public fun configure(transport: ShippingTransport?) {
            configuredTransport = transport
        }

        /** Spool used by the worker; route a [ShippingSpoolSink] here. */
        public fun spoolFile(context: Context): File =
            File(File(context.applicationContext.filesDir, SPOOL_DIRECTORY), SPOOL_FILE_NAME)

        /** Convenience spool bound to the worker's file. */
        public fun createSpool(context: Context): ShippingSpool = ShippingSpool(spoolFile(context))

        /**
         * Enqueues one unique run constrained to network availability. Existing
         * pending work is kept, so calling this on every app start is cheap.
         */
        public fun enqueue(context: Context, workName: String = UNIQUE_WORK_NAME) {
            val request = OneTimeWorkRequestBuilder<LogShippingWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
        }
    }
}
