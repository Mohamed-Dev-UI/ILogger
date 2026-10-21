package com.mohamed.dev.ui.ilogger.logging

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.ilogger.core.ILogger
import javax.inject.Inject

/**
 * Hilt application entry point. The logger is injected (singleton graph) and
 * flushed when the OS may freeze the process; the database and logger lifetime
 * are both `SingletonComponent`-scoped.
 */
@HiltAndroidApp
class ILoggerApplication : Application() {

    @Inject
    lateinit var logger: ILogger

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        logger.flush()
    }
}
