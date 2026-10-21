package com.mohamed.dev.ui.ilogger.logging

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ilogger.annotations.DispatchMode
import io.ilogger.core.Actor
import io.ilogger.core.ILogger
import io.ilogger.core.LogEvent
import io.ilogger.core.onEvent
import io.ilogger.core.withActor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * The generated `HiltTestDatabase_ILogged_KoinModule` resolves the database and
 * logger from Koin and returns the wrapped DAO — no `db.logged(logger)` here.
 */
@RunWith(AndroidJUnit4::class)
class KoinDaoBindingsTest {

    @Test
    fun generatedKoinModuleProvidesWrappedDao() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val events = mutableListOf<LogEvent>()
        val logger = ILogger.create {
            dispatch = DispatchMode.INLINE
            onEvent { events.add(it) }
        }

        val app = koinApplication {
            modules(
                HiltTestDatabase_ILogged_KoinModule.module,
                module {
                    single<HiltTestDatabase> {
                        Room.inMemoryDatabaseBuilder<HiltTestDatabase>(context).build()
                    }
                    single<ILogger> { logger }
                },
            )
        }

        try {
            val dao = app.koin.get<HiltTestTaskDao>()
            withActor(Actor.user("koin-test")) {
                dao.insert(HiltTestTask(title = "koin"))
            }
            assertTrue(
                "expected a ${TestDaoActions.CREATED} event, got ${events.map { it.action }}",
                events.any { it.action == TestDaoActions.CREATED },
            )
        } finally {
            app.close()
            logger.close()
        }
    }
}
