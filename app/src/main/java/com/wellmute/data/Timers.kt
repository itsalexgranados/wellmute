package com.wellmute.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A daily limit for one app. Once used up, the app is paused until midnight. */
@Entity(tableName = "app_timers")
data class AppTimer(
    @PrimaryKey val packageName: String,
    val dailyLimitMillis: Long,
    /** Unused: whether to warn is now one setting for every timer (TimerSettings). Kept so the table needs no migration. */
    val warnBeforeEnd: Boolean = true,
)

@Dao
interface TimerDao {

    @Query("SELECT * FROM app_timers")
    fun observeAll(): Flow<List<AppTimer>>

    @Query("SELECT * FROM app_timers")
    suspend fun all(): List<AppTimer>

    @Query("SELECT * FROM app_timers WHERE packageName = :packageName")
    suspend fun get(packageName: String): AppTimer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(timer: AppTimer)

    @Query("DELETE FROM app_timers WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
