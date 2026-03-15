package com.spectravision.analyzer.data

import android.content.Context
import androidx.room.*

@Dao
interface SpectrumDao {
    @Query("SELECT * FROM spectra ORDER BY timestamp DESC")
    suspend fun getAll(): List<SpectrumRecord>

    @Insert
    suspend fun insert(record: SpectrumRecord): Long

    @Delete
    suspend fun delete(record: SpectrumRecord)
}

@Dao
interface CalibrationDao {
    @Query("SELECT * FROM calibrations ORDER BY timestamp DESC")
    suspend fun getAll(): List<CalibrationRecord>

    @Query("SELECT * FROM calibrations WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): CalibrationRecord?

    @Insert
    suspend fun insert(record: CalibrationRecord): Long

    @Query("UPDATE calibrations SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE calibrations SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)
}

@Database(
    entities = [SpectrumRecord::class, CalibrationRecord::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spectrumDao(): SpectrumDao
    abstract fun calibrationDao(): CalibrationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "spectravision.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
