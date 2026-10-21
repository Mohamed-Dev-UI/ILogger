package com.mohamed.dev.ui.ilogger.logging

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.components.SingletonComponent
import io.ilogger.annotations.Log
import io.ilogger.annotations.LogDao
import io.ilogger.annotations.LogMeta
import io.ilogger.core.Actor
import io.ilogger.core.ILogger
import io.ilogger.core.withActor
import io.ilogger.room.RoomLogEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import javax.inject.Singleton

object TestDaoActions {
    const val CREATED = "test.task.created"
}

@Entity(tableName = "hilt_test_tasks")
data class HiltTestTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
)

/**
 * The processor generates `TestDatabase_ILogged_HiltModule` for this DAO;
 * nothing in this file provides [HiltTestTaskDao] manually.
 */
@LogDao(hilt = true, koin = true)
@Dao
interface HiltTestTaskDao {

    @Insert
    @Log(action = TestDaoActions.CREATED, metadata = [LogMeta("task.title")])
    suspend fun insert(task: HiltTestTask): Long
}

@Database(entities = [HiltTestTask::class], version = 1, exportSchema = false)
abstract class HiltTestDatabase : RoomDatabase() {
    abstract fun taskDao(): HiltTestTaskDao
}

@Module
@InstallIn(SingletonComponent::class)
object HiltTestDatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): HiltTestDatabase =
        Room.inMemoryDatabaseBuilder<HiltTestDatabase>(context).build()
}

/**
 * Proves the generated Hilt module is enough to inject an already-wrapped DAO:
 * no `db.logged(logger)` at the injection point or the call site.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class HiltDaoBindingsTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var dao: HiltTestTaskDao

    @Inject
    lateinit var logger: ILogger

    @Inject
    lateinit var database: AppDatabase

    @Before
    fun setUp() {
        hilt.inject()
        runBlocking { database.logDao().purgeBefore(Long.MAX_VALUE) }
    }

    @Test
    fun injectedDaoIsAlreadyWrappedByTheGeneratedModule() = runBlocking<Unit> {
        withActor(Actor.user("hilt-test")) {
            dao.insert(HiltTestTask(title = "injected"))
        }
        logger.flush()

        val rows = awaitCreatedRow()
        val created = rows.first { it.action == TestDaoActions.CREATED }
        assertEquals("hilt-test", created.actorId)
        assertTrue(
            "payload=${created.payload}",
            created.payload.contains("injected"),
        )
    }

    private suspend fun awaitCreatedRow(): List<RoomLogEntity> = withTimeout(5_000) {
        var rows: List<RoomLogEntity> = emptyList()
        while (rows.none { it.action == TestDaoActions.CREATED }) {
            rows = database.logDao().recent(limit = 20)
            if (rows.none { it.action == TestDaoActions.CREATED }) delay(25)
        }
        rows
    }
}
