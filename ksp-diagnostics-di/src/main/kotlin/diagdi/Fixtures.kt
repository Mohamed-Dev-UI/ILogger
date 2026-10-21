package diagdi

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import io.ilogger.annotations.Log
import io.ilogger.annotations.LogDao

@Entity(tableName = "dup_rows")
data class DupRow(
    @PrimaryKey val id: Long,
    val title: String,
)

@LogDao(hilt = true)
@Dao
interface DupDao {

    @Insert
    @Log(action = "dup.created", entity = DupRow::class)
    suspend fun insert(row: DupRow): Long
}

@Database(entities = [DupRow::class], version = 1, exportSchema = false)
abstract class DupDbOne : RoomDatabase() {
    abstract fun dupDao(): DupDao
}

/**
 * The same DAO exposed by a second database would produce two Hilt bindings
 * for `DupDao`; the processor must report it and skip the second module.
 */
// EXPECTED: Duplicate Hilt binding for DAO 'diagdi.DupDao'
@Database(entities = [DupRow::class], version = 1, exportSchema = false)
abstract class DupDbTwo : RoomDatabase() {
    abstract fun dupDao(): DupDao
}

@Entity(tableName = "koin_dup_rows")
data class KoinDupRow(
    @PrimaryKey val id: Long,
    val title: String,
)

@LogDao(koin = true)
@Dao
interface KoinDupDao {

    @Insert
    @Log(action = "koin.dup.created", entity = KoinDupRow::class)
    suspend fun insert(row: KoinDupRow): Long
}

@Database(entities = [KoinDupRow::class], version = 1, exportSchema = false)
abstract class KoinDupDbOne : RoomDatabase() {
    abstract fun koinDupDao(): KoinDupDao
}

// EXPECTED: Duplicate Koin binding for DAO 'diagdi.KoinDupDao'
@Database(entities = [KoinDupRow::class], version = 1, exportSchema = false)
abstract class KoinDupDbTwo : RoomDatabase() {
    abstract fun koinDupDao(): KoinDupDao
}
