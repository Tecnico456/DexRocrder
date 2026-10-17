package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern MediaStore API helper that writes recordings directly to Movies/DexRecorder
 * without needing legacy WRITE_EXTERNAL_STORAGE permissions.
 */
object MediaStoreHelper {
    private const val TAG = "MediaStoreHelper"

    data class MediaStoreOutput(
        val uri: Uri,
        val pfd: ParcelFileDescriptor,
        val displayName: String,
        val approximatePath: String
    )

    fun createVideoOutput(context: Context): MediaStoreOutput? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "DexRecorder_$timeStamp.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DexRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        return try {
            val uri = context.contentResolver.insert(collection, values) ?: return null
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return null
            val approxPath = File(context.getExternalFilesDir(null), fileName).absolutePath
            MediaStoreOutput(uri, pfd, fileName, approxPath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MediaStore entry for video recording", e)
            null
        }
    }

    fun finishPendingVideo(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            try {
                context.contentResolver.update(uri, values, null, null)
                Log.d(TAG, "Video marked as non-pending in MediaStore: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing MediaStore pending status", e)
            }
        }
    }
}
