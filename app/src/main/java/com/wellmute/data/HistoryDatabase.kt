package com.wellmute.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Days are stored as epoch days (days since 1970-01-01) in the device's time zone at the time. */

/** Foreground time and opens of one app during one hour of one day. */
@Entity(tableName = "app_usage", primaryKeys = ["day", "hour", "packageName"])
data class AppUsageRow(
    val day: Long,
    val hour: Int,
    val packageName: String,
    val foregroundMillis: Long,
    val opens: Int,
)

/** Notifications one app posted during one hour of one day. */
@Entity(tableName = "notification_counts", primaryKeys = ["day", "hour", "packageName"])
data class NotificationCountRow(
    val day: Long,
    val hour: Int,
    val packageName: String,
    val count: Int,
)

@Entity(tableName = "day_totals")
data class DayTotalsRow(
    @PrimaryKey val day: Long,
    val unlocks: Int,
    /** The longest stretch of one app in front, and which app it was (0 and null before version 6). */
    val longestSessionMillis: Long = 0,
    val longestSessionPackage: String? = null,
    /** Screen time during Bedtime's scheduled hours; null when no Bedtime schedule covered the day. */
    val afterBedtimeMillis: Long? = null,
    /** Screen time while Focus mode was on (breaks don't count); null when Focus wasn't on that day. */
    val duringFocusMillis: Long? = null,
    /** When the phone was first used that morning (epoch millis); null when unknown. */
    val firstPickupMillis: Long? = null,
    /** Time Focus mode was on that day, the part of it the phone wasn't used, and the longest such stretch; null when Focus wasn't on. */
    val focusMillis: Long? = null,
    val focusIdleMillis: Long? = null,
    val focusLongestIdleMillis: Long? = null,
)

/** A Focus break: when it started, when it was meant to end, and when it was ended early (null if it ran its course). */
@Entity(tableName = "focus_breaks")
data class FocusBreakRow(@PrimaryKey val start: Long, val plannedEnd: Long, val endedAt: Long? = null)

/** An attempt to open a distracting app during Focus, which the pause screen stopped. */
@Entity(tableName = "focus_blocks")
data class FocusBlockRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long, val packageName: String)

/** One app's use during Focus on one day: time and opens. */
@Entity(tableName = "focus_app_days", primaryKeys = ["day", "packageName"])
data class FocusAppDayRow(val day: Long, val packageName: String, val millis: Long, val opens: Int)

/** One stretch of Focus mode being on, in epoch millis; [end] is null while it still is. */
@Entity(tableName = "focus_periods")
data class FocusPeriodRow(@PrimaryKey val start: Long, val end: Long?)

/** One app's screen time on one day. */
data class AppDayValue(val day: Long, val packageName: String, val value: Long)

/** One app's totals over a period, read back from the hourly rows. */
data class AppTotals(
    val packageName: String,
    val foregroundMillis: Long,
    val opens: Int,
)

data class PackageCount(val packageName: String, val count: Int)

data class DayValue(val day: Long, val value: Long)

data class HourValue(val hour: Int, val value: Long)

@Dao
interface HistoryDao {

    // Usage is always recalculated from Android's events for a whole day, then replaces what we had.
    @Transaction
    suspend fun replaceDay(day: Long, usage: List<AppUsageRow>, totals: DayTotalsRow) {
        deleteUsage(day)
        insertUsage(usage)
        insertTotals(totals)
    }

    @Query("DELETE FROM app_usage WHERE day = :day")
    suspend fun deleteUsage(day: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsage(rows: List<AppUsageRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTotals(row: DayTotalsRow)

    @Query("SELECT COALESCE(SUM(foregroundMillis), 0) FROM app_usage WHERE day = :day")
    suspend fun totalMillis(day: Long): Long

    @Transaction
    suspend fun incrementNotifications(day: Long, hour: Int, packageName: String) {
        if (addNotification(day, hour, packageName) == 0) {
            insertNotificationCount(NotificationCountRow(day, hour, packageName, 1))
        }
    }

    @Query("UPDATE notification_counts SET count = count + 1 WHERE day = :day AND hour = :hour AND packageName = :packageName")
    suspend fun addNotification(day: Long, hour: Int, packageName: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationCount(row: NotificationCountRow)

    @Query(
        "SELECT packageName, SUM(foregroundMillis) AS foregroundMillis, SUM(opens) AS opens " +
            "FROM app_usage WHERE day BETWEEN :from AND :to GROUP BY packageName",
    )
    fun observeAppTotals(from: Long, to: Long): Flow<List<AppTotals>>

    @Query(
        "SELECT packageName, SUM(count) AS count FROM notification_counts " +
            "WHERE day BETWEEN :from AND :to GROUP BY packageName",
    )
    fun observeNotificationCounts(from: Long, to: Long): Flow<List<PackageCount>>

    @Query("SELECT COALESCE(SUM(unlocks), 0) FROM day_totals WHERE day BETWEEN :from AND :to")
    fun observeUnlocks(from: Long, to: Long): Flow<Int>

    @Query("SELECT * FROM day_totals WHERE day BETWEEN :from AND :to")
    fun observeDayTotalRows(from: Long, to: Long): Flow<List<DayTotalsRow>>

    @Query("SELECT day, packageName, SUM(foregroundMillis) AS value FROM app_usage WHERE day BETWEEN :from AND :to GROUP BY day, packageName")
    fun observeAppDailyAll(from: Long, to: Long): Flow<List<AppDayValue>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFocusPeriod(row: FocusPeriodRow)

    @Query("SELECT * FROM focus_periods WHERE `end` IS NULL ORDER BY start DESC LIMIT 1")
    suspend fun openFocusPeriod(): FocusPeriodRow?

    /** Focus periods overlapping [from, to). */
    @Query("SELECT * FROM focus_periods WHERE start < :to AND (`end` IS NULL OR `end` > :from)")
    suspend fun focusPeriodsBetween(from: Long, to: Long): List<FocusPeriodRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFocusBreak(row: FocusBreakRow)

    /** The break running at [now], if any. */
    @Query("SELECT * FROM focus_breaks WHERE start <= :now AND plannedEnd > :now AND endedAt IS NULL ORDER BY start DESC LIMIT 1")
    suspend fun runningFocusBreak(now: Long): FocusBreakRow?

    @Query("SELECT * FROM focus_breaks WHERE start BETWEEN :from AND :to")
    fun observeFocusBreaks(from: Long, to: Long): Flow<List<FocusBreakRow>>

    @Insert
    suspend fun addFocusBlock(row: FocusBlockRow)

    @Query("SELECT * FROM focus_blocks WHERE at BETWEEN :from AND :to")
    fun observeFocusBlocks(from: Long, to: Long): Flow<List<FocusBlockRow>>

    @Query("DELETE FROM focus_app_days WHERE day = :day")
    suspend fun deleteFocusAppDay(day: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFocusAppDays(rows: List<FocusAppDayRow>)

    @Transaction
    suspend fun replaceFocusAppDay(day: Long, rows: List<FocusAppDayRow>) {
        deleteFocusAppDay(day)
        insertFocusAppDays(rows)
    }

    @Query("SELECT * FROM focus_app_days WHERE day BETWEEN :from AND :to")
    fun observeFocusAppDays(from: Long, to: Long): Flow<List<FocusAppDayRow>>

    @Query(
        "SELECT day, SUM(foregroundMillis) AS value FROM app_usage " +
            "WHERE day BETWEEN :from AND :to GROUP BY day",
    )
    fun observeDailyTotals(from: Long, to: Long): Flow<List<DayValue>>

    /** Screen time on [day] before [hour] (hours since midnight), to compare part of a day. */
    @Query("SELECT COALESCE(SUM(foregroundMillis), 0) FROM app_usage WHERE day = :day AND hour < :hour")
    fun observeTotalBefore(day: Long, hour: Int): Flow<Long>

    @Query("SELECT MIN(day) FROM app_usage")
    fun observeFirstDay(): Flow<Long?>

    // Per-app detail: the three metrics by day and by hour.

    @Query(
        "SELECT day, SUM(foregroundMillis) AS value FROM app_usage " +
            "WHERE packageName = :packageName AND day BETWEEN :from AND :to GROUP BY day",
    )
    fun observeAppDailyMillis(packageName: String, from: Long, to: Long): Flow<List<DayValue>>

    @Query(
        "SELECT day, SUM(opens) AS value FROM app_usage " +
            "WHERE packageName = :packageName AND day BETWEEN :from AND :to GROUP BY day",
    )
    fun observeAppDailyOpens(packageName: String, from: Long, to: Long): Flow<List<DayValue>>

    @Query(
        "SELECT day, SUM(count) AS value FROM notification_counts " +
            "WHERE packageName = :packageName AND day BETWEEN :from AND :to GROUP BY day",
    )
    fun observeAppDailyNotifications(packageName: String, from: Long, to: Long): Flow<List<DayValue>>

    @Query("SELECT hour, foregroundMillis AS value FROM app_usage WHERE packageName = :packageName AND day = :day")
    fun observeAppHourlyMillis(packageName: String, day: Long): Flow<List<HourValue>>

    @Query("SELECT hour, opens AS value FROM app_usage WHERE packageName = :packageName AND day = :day")
    fun observeAppHourlyOpens(packageName: String, day: Long): Flow<List<HourValue>>

    @Query("SELECT hour, count AS value FROM notification_counts WHERE packageName = :packageName AND day = :day")
    fun observeAppHourlyNotifications(packageName: String, day: Long): Flow<List<HourValue>>
}

@Database(
    entities = [
        AppUsageRow::class, NotificationCountRow::class, DayTotalsRow::class, AppTimer::class,
        FocusPeriodRow::class, FocusBreakRow::class, FocusBlockRow::class, FocusAppDayRow::class,
    ],
    version = 1,
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    abstract fun timerDao(): TimerDao

    companion object {
        /**
         * Every schema change from here on ships with a migration (`addMigrations`): history is
         * meant to last for years, so the database is never wiped and recreated on update.
         */
        fun create(context: Context): HistoryDatabase =
            Room.databaseBuilder(context, HistoryDatabase::class.java, "history.db").build()
    }
}
