package com.mohamed.dev.ui.ilogger.logging

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Hilt requires instrumented tests to run against [HiltTestApplication] rather
 * than the app's `@HiltAndroidApp` class. The production module graph
 * ([LoggingModule], [EventBuffer], the generated repository wrapper) is still
 * installed by Hilt's aggregation, so tests exercise the real bindings.
 */
class ILoggerTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
