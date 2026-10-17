package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a recorded video in DexRecorder.
 * Supports soft-delete with 3-day retention in the Trash (Lixeira).
 */
@Entity(tableName = "recorded_videos")
data class RecordedVideo(
    @PrimaryKey(autoGenerate = false)
    val id: Long = 0,
    val title: String,
    val filePath: String,
    val uriString: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val fps: Int,
    val audioSource: String, // "INTERNAL", "MIC", "MIXED", "MUTE"
    val createdAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null
)
