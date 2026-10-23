package io.ilogger.android

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ilogger.annotations.LogLevel
import io.ilogger.core.ActorType
import io.ilogger.core.ILogger
import io.ilogger.core.LogEvent
import io.ilogger.core.LogSink
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device coverage for the integration points that cannot be exercised on the
 * JVM: `ProcessLifecycleOwner` delivery, real SharedPreferences persistence,
 * and file writes under the app's private storage.
 */
@RunWith(AndroidJUnit4::class)
class AndroidIntegrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun process_stop_flushes_the_logger() {
        val flushed = CountDownLatch(1)
        val logger = ILogger.create {
            sink(
                object : LogSink {
                    override suspend fun write(event: LogEvent) = Unit
                    override suspend fun flush() {
                        flushed.countDown()
                    }
                },
            )
        }
        lateinit var hooks: AndroidLifecycleHooks

        instrumentation.runOnMainSync {
            hooks = AndroidLifecycleHooks(logger).attach()
            val lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }

        assertTrue("flush should run after process ON_STOP", flushed.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { hooks.close() }
        logger.close()
    }

    @Test
    fun actor_provider_persists_the_session_id() {
        val context = instrumentation.targetContext
        val preferencesName = "ilogger-test-session"
        try {
            val first = AndroidActorProvider(context, preferencesName)
            val second = AndroidActorProvider(context, preferencesName)

            assertEquals(first.sessionId, second.sessionId)
            assertEquals(ActorType.AUTOMATION, second.currentActor().type)
        } finally {
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun file_sink_writes_jsonl_under_files_dir() = runBlocking {
        val context = instrumentation.targetContext
        val name = "instrumented-${System.nanoTime()}.jsonl"
        val sink = AndroidFileSink.create(context, name = name)
        try {
            sink.write(event("e1"))
            sink.flush()

            val file = File(File(context.filesDir, "ilogger"), name)
            assertTrue(file.exists())
            assertTrue(file.readLines().single().contains("\"id\":\"e1\""))
        } finally {
            sink.close()
            File(File(context.filesDir, "ilogger"), name).delete()
        }
    }

    @Test
    fun logcat_sink_default_writer_does_not_throw() = runBlocking {
        // Formatting/priority mapping is asserted deterministically on the JVM;
        // this proves the default android.util.Log path works on a device.
        LogcatSink(tag = "ILoggerInstrumented").write(event("logcat"))
    }

    private fun event(id: String): LogEvent = LogEvent(
        id = id,
        timestampMillis = 1L,
        level = LogLevel.AUDIT,
        action = "android.instrumented",
    )
}
