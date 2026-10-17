package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.RecordedVideo
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordedVideoDao {

    @Query("SELECT * FROM recorded_videos WHERE isDeleted = 0 ORDER BY createdAt DESC")
    fun getActiveVideos(): Flow<List<RecordedVideo>>

    @Query("SELECT * FROM recorded_videos WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getTrashVideos(): Flow<List<RecordedVideo>>

    @Query("SELECT id FROM recorded_videos WHERE isDeleted = 1")
    suspend fun getTrashVideoIds(): List<Long>

    @Query("SELECT * FROM recorded_videos WHERE id = :id LIMIT 1")
    fun getVideoById(id: Long): Flow<RecordedVideo?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: RecordedVideo): Long

    @Update
    suspend fun updateVideo(video: RecordedVideo)

    @Query("UPDATE recorded_videos SET isDeleted = 1, deletedAt = :deletedAt WHERE id = :id")
    suspend fun moveToTrash(id: Long, deletedAt: Long = System.currentTimeMillis())

    @Query("UPDATE recorded_videos SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Long)

    @Query("DELETE FROM recorded_videos WHERE id = :id")
    suspend fun deletePermanently(id: Long)

    @Query("SELECT * FROM recorded_videos WHERE isDeleted = 1 AND deletedAt < :cutoffTime")
    suspend fun getExpiredVideos(cutoffTime: Long): List<RecordedVideo>

    @Query("DELETE FROM recorded_videos WHERE isDeleted = 1 AND deletedAt < :cutoffTime")
    suspend fun purgeExpiredTrash(cutoffTime: Long)

    @Query("DELETE FROM recorded_videos WHERE isDeleted = 1")
    suspend fun clearTrash()
}
