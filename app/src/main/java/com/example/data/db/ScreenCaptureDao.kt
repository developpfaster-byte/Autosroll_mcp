package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScreenCaptureDao {
    @Query("SELECT * FROM screen_captures ORDER BY timestamp DESC")
    fun getAllCaptures(): Flow<List<ScreenCaptureEntity>>

    @Query("SELECT * FROM screen_captures WHERE id = :id LIMIT 1")
    suspend fun getCaptureById(id: Long): ScreenCaptureEntity?

    @Query("SELECT * FROM screen_captures ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestCapture(): ScreenCaptureEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCapture(entity: ScreenCaptureEntity): Long

    @Query("DELETE FROM screen_captures WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM screen_captures")
    suspend fun clearAll()
}
