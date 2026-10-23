package com.mohamed.dev.ui.ilogger.logging

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import io.ilogger.android.AndroidLifecycleHooks
import io.ilogger.android.CrashHandoff
import io.ilogger.core.ILogger
import javax.inject.Inject

/**
 * Hilt application entry point. The logger is injected (singleton graph) and
 * flushed when the OS may freeze the process; the database and logger lifetime
 * are both `SingletonComponent`-scoped.
 *
 * `ilogger-android` owns the Android-specific lifecycle: [AndroidLifecycleHooks]
 * flushes on process `ON_STOP`, [CrashHandoff] persists the recent-event ring
 * on an uncaught exception, and pending crash reports are surfaced here on the
 * next launch.
 */
@HiltAndroidApp
class ILoggerApplication : Application() {

    @Inject
    lateinit var logger: ILogger

    @Inject
    lateinit var crashHandoff: CrashHandoff

    @Inject
    lateinit var lifecycleHooks: AndroidLifecycleHooks

    override fun onCreate() {
        super.onCreate()
        crashHandoff.drain().forEach { report ->
            Log.w(
                "ILogger",
                "Recovered crash report ${report.file.name} with ${report.lines.size} event(s)",
            )
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        logger.flush()
    }
}
