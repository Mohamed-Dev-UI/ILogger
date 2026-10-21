package com.mohamed.dev.ui.ilogger.logging

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.ilogger.core.Actor
import io.ilogger.core.ILogger
import io.ilogger.core.withActor
import io.ilogger.room.RoomLogEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Boots the application's real Hilt graph — [LoggingModule]'s database, logger,
 * UI buffer and generated [TaskRepository] wrapper — and asserts the audit
 * event reaches the on-device Room file, not a test double.
 *
 * The event is read twice: once through the injected [AppDatabase] (the DAO the
 * singleton logger writes with) and once through a brand-new connection to the
 * same database file, which proves the row was actually persisted and is
 * visible across connections.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RoomLoggingInstrumentedTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var logger: ILogger

    @Inject
    lateinit var database: AppDatabase

    @Inject
    lateinit var repository: TaskRepository

    @Inject
    lateinit var buffer: EventBuffer

    private var reopened: AppDatabase? = null

    @Before
    fun setUp() {
        hilt.inject()
        // The demo app shares one on-device database across runs; start clean.
        runBlocking { database.logDao().purgeBefore(Long.MAX_VALUE) }
    }

    @After
    fun tearDown() {
        reopened?.close()
    }

    // DEX (before version 040) rejects spaces in synthetic class names, so this
    // name is a plain identifier rather than a backticked sentence.
    @Test
    fun instrumentedEventIsVisibleThroughTheSharedGraphAndANewConnection() =
        runBlocking<Unit> {
            withActor(Actor.user("android-test", name = "Instrumented")) {
                repository.create("written through the Hilt graph")
            }
            logger.flush()

            val rows = awaitCreatedRow()
            val created = rows.first { it.action == TaskActions.CREATED }

            assertEquals("AUDIT", created.level)
            assertEquals("Task", created.entity)
            assertEquals("android-test", created.actorId)
            assertTrue(
                "payload=${created.payload}",
                created.payload.contains("\"action\":\"task.created\""),
            )
            assertTrue(
                "payload=${created.payload}",
                created.payload.contains("written through the Hilt graph"),
            )

            // The injected logger also fans out to the app's UI buffer, proving
            // the repository is wired to the same singleton as this test.
            assertTrue(
                "buffer=${buffer.lines.value}",
                buffer.lines.value.any { line -> line.contains(TaskActions.CREATED) },
            )

            // A separate connection to the same file proves a real DAO write.
            reopened = Room.databaseBuilder<AppDatabase>(
                InstrumentationRegistry.getInstrumentation().targetContext,
                "ilogger-demo.db",
            ).build()

            val onDisk = reopened!!.logDao().recent(limit = 50)
            assertTrue(
                "rows on disk=${onDisk.map { it.action }}",
                onDisk.any { row -> row.eventId == created.eventId },
            )
        }

    private suspend fun awaitCreatedRow(): List<RoomLogEntity> = withTimeout(5_000) {
        var rows: List<RoomLogEntity> = emptyList()
        while (rows.none { it.action == TaskActions.CREATED }) {
            rows = database.logDao().recent(limit = 50)
            if (rows.none { it.action == TaskActions.CREATED }) delay(25)
        }
        rows
    }
}
