package com.example.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.example.DexRecorderApplication
import com.example.model.RecordedVideo
import com.example.storage.MediaStoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

object VideoEditorManager {
    private const val TAG = "VideoEditorManager"

    sealed class EditResult {
        data class Success(val newVideo: RecordedVideo) : EditResult()
        data class Error(val message: String) : EditResult()
    }

    /**
     * Performs fast, lossless video trimming using MediaExtractor and MediaMuxer.
     * Extracts frames between [startMs] and [endMs], adjusts timestamps, and saves via MediaStore.
     */
    suspend fun trimVideo(
        context: Context,
        sourceVideo: RecordedVideo,
        startMs: Long,
        endMs: Long,
        playbackSpeed: Float = 1.0f
    ): EditResult = withContext(Dispatchers.IO) {
        val startUs = startMs * 1000L
        val endUs = endMs * 1000L

        if (endUs <= startUs) {
            return@withContext EditResult.Error("O tempo final deve ser maior que o inicial.")
        }

        val output = MediaStoreHelper.createVideoOutput(context)
            ?: return@withContext EditResult.Error("Falha ao preparar arquivo de saída no MediaStore.")

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            extractor = MediaExtractor()
            if (sourceVideo.uriString.isNotBlank()) {
                extractor.setDataSource(context, Uri.parse(sourceVideo.uriString), null)
            } else {
                extractor.setDataSource(sourceVideo.filePath)
            }

            muxer = MediaMuxer(output.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackCount = extractor.trackCount
            val trackMap = mutableMapOf<Int, Int>()

            var videoTrackIndex = -1
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val muxerTrack = muxer.addTrack(format)
                    trackMap[i] = muxerTrack
                    if (mime.startsWith("video/")) {
                        videoTrackIndex = i
                    }
                }
            }

            if (trackMap.isEmpty()) {
                return@withContext EditResult.Error("Nenhuma faixa de mídia compatível encontrada.")
            }

            muxer.start()

            // Seek to start position
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            for (track in trackMap.keys) {
                extractor.selectTrack(track)
            }

            var minPtsUs = -1L

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break

                val muxerTrack = trackMap[trackIndex]
                val sampleTimeUs = extractor.sampleTime

                if (sampleTimeUs > endUs) {
                    extractor.advance()
                    continue
                }

                if (sampleTimeUs < startUs && trackIndex != videoTrackIndex) {
                    extractor.advance()
                    continue
                }

                if (minPtsUs < 0) {
                    minPtsUs = sampleTimeUs
                }

                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)

                if (bufferInfo.size < 0) {
                    break
                }

                // Adjust PTS for trimming and optional speed adjustment
                val normalizedPts = (sampleTimeUs - minPtsUs).coerceAtLeast(0L)
                bufferInfo.presentationTimeUs = (normalizedPts / playbackSpeed).toLong()
                bufferInfo.flags = extractor.sampleFlags

                if (muxerTrack != null) {
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                }

                extractor.advance()
            }

            muxer.stop()
            output.pfd.close()
            MediaStoreHelper.finishPendingVideo(context, output.uri)

            val newDurationMs = ((endMs - startMs) / playbackSpeed).toLong()
            val newFile = File(output.approximatePath)
            val newSize = if (newFile.exists()) newFile.length() else sourceVideo.sizeBytes
            val mediaId = output.uri.lastPathSegment?.toLongOrNull() ?: System.currentTimeMillis()

            val newRecord = RecordedVideo(
                id = mediaId,
                title = "Trim_${sourceVideo.title}",
                filePath = output.approximatePath,
                uriString = output.uri.toString(),
                durationMs = newDurationMs,
                sizeBytes = newSize,
                width = sourceVideo.width,
                height = sourceVideo.height,
                fps = sourceVideo.fps,
                audioSource = sourceVideo.audioSource,
                createdAt = System.currentTimeMillis()
            )

            val app = context.applicationContext as? DexRecorderApplication
            app?.videoRepository?.saveRecordedVideo(newRecord)

            EditResult.Success(newRecord)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to edit video", e)
            EditResult.Error("Erro durante a edição: ${e.message}")
        } finally {
            try {
                extractor?.release()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }
}
