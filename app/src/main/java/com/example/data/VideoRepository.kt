package com.example.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.example.model.RecordedVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository for managing recorded videos.
 *
 * TAREFA 3: Correção de Duplicidade na Aba de Vídeos.
 * - Busca dados exclusivamente no MediaStore.Video.Media.EXTERNAL_CONTENT_URI
 *   filtrando pela pasta do aplicativo (RelativePath = 'Movies/DexRecorder/'), sem ler pastas locais.
 * - Limpa explicitamente a lista em memória antes de recarregar: videoList.clear().
 * - Mantém sincronia atômica com IDs únicos do MediaStore para eliminar qualquer duplicidade.
 */
class VideoRepository(
    private val dao: RecordedVideoDao,
    private val context: Context
) {
    companion object {
        private const val TAG = "DexVideoRepo"
    }

    private val _activeVideos = MutableStateFlow<List<RecordedVideo>>(emptyList())
    val activeVideos: StateFlow<List<RecordedVideo>> = _activeVideos.asStateFlow()

    val trashVideos: Flow<List<RecordedVideo>> = dao.getTrashVideos()

    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    init {
        registerMediaStoreObserver()
        refreshVideos()
    }

    private fun registerMediaStoreObserver() {
        try {
            val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    Log.d(TAG, "Notificação de alteração no MediaStore recebida. Recarregando vídeos...")
                    refreshVideos()
                }
            }
            context.contentResolver.registerContentObserver(uri, true, observer)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao registrar ContentObserver para MediaStore", e)
        }
    }

    /**
     * Dispara recarregamento assíncrono dos vídeos do MediaStore.
     */
    fun refreshVideos() {
        repositoryScope.launch {
            loadVideosFromMediaStore()
        }
    }

    /**
     * TAREFA 3:
     * 1. Limpa explicitamente a lista em memória antes de recarregar: videoList.clear()
     * 2. Consulta exclusivamente MediaStore.Video.Media.EXTERNAL_CONTENT_URI filtrando por RelativePath = 'Movies/DexRecorder/'
     * 3. Não lê pastas locais (context.filesDir ou cache)
     */
    suspend fun loadVideosFromMediaStore(): List<RecordedVideo> = withContext(Dispatchers.IO) {
        val videoList = mutableListOf<RecordedVideo>()
        // Limpe explicitamente a lista em memória antes de recarregar:
        videoList.clear()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.Video.Media.RELATIVE_PATH)
        }

        // Filtra exclusivamente pela pasta do aplicativo (Movies/DexRecorder/)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        } else {
            "${MediaStore.Video.Media.DATA} LIKE ?"
        }

        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("Movies/DexRecorder/%", "Movies/DexRecorder/")
        } else {
            arrayOf("%Movies/DexRecorder/%")
        }

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                collection,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "DexRecorder_$mediaId.mp4"
                    val filePath = cursor.getString(dataCol) ?: ""
                    val duration = cursor.getLong(durCol)
                    val size = cursor.getLong(sizeCol)
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)
                    val dateAddedSec = cursor.getLong(dateCol)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        mediaId
                    )

                    val recordedVideo = RecordedVideo(
                        id = mediaId,
                        title = displayName,
                        filePath = filePath,
                        uriString = contentUri.toString(),
                        durationMs = duration,
                        sizeBytes = size,
                        width = if (width > 0) width else 1920,
                        height = if (height > 0) height else 1080,
                        fps = 60,
                        audioSource = "DEX_RECORDER",
                        createdAt = if (dateAddedSec > 0) dateAddedSec * 1000L else System.currentTimeMillis()
                    )

                    videoList.add(recordedVideo)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar MediaStore.Video.Media.EXTERNAL_CONTENT_URI", e)
        }

        // Filtra vídeos que estão marcados na Lixeira
        val trashedIds = try {
            dao.getTrashVideoIds().toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val filteredVideos = videoList.filterNot { it.id in trashedIds }
        _activeVideos.value = filteredVideos
        Log.d(TAG, "Lista de vídeos carregada do MediaStore com sucesso: ${filteredVideos.size} vídeos únicos")
        return@withContext filteredVideos
    }

    suspend fun saveRecordedVideo(video: RecordedVideo): Long = withContext(Dispatchers.IO) {
        val id = dao.insertVideo(video)
        refreshVideos()
        id
    }

    suspend fun moveToTrash(id: Long) = withContext(Dispatchers.IO) {
        dao.moveToTrash(id, System.currentTimeMillis())
        refreshVideos()
    }

    suspend fun restoreFromTrash(id: Long) = withContext(Dispatchers.IO) {
        dao.restoreFromTrash(id)
        refreshVideos()
    }

    suspend fun deletePermanently(video: RecordedVideo) = withContext(Dispatchers.IO) {
        try {
            if (video.uriString.isNotBlank()) {
                context.contentResolver.delete(Uri.parse(video.uriString), null, null)
            } else if (video.filePath.isNotBlank()) {
                val file = File(video.filePath)
                if (file.exists()) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao deletar mídia física do MediaStore", e)
        }
        dao.deletePermanently(video.id)
        refreshVideos()
    }

    suspend fun purgeExpiredTrash() = withContext(Dispatchers.IO) {
        val threeDaysAgo = System.currentTimeMillis() - (3L * 24L * 60L * 60L * 1000L)
        val expired = dao.getExpiredVideos(threeDaysAgo)
        for (video in expired) {
            deletePermanently(video)
        }
        refreshVideos()
    }

    suspend fun updateVideo(video: RecordedVideo) = withContext(Dispatchers.IO) {
        dao.updateVideo(video)
        refreshVideos()
    }
}
