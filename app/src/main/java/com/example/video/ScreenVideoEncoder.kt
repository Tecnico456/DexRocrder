package com.example.video

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Hardware-accelerated screen video encoder using MediaCodec and MediaProjection.
 * Drains output on a completely separate Coroutine Scope (Dispatchers.IO) to ensure
 * zero thread contention with the Audio subsystem.
 */
class ScreenVideoEncoder(
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val useHevc: Boolean,
    private val muxerManager: MediaMuxerManager
) {
    companion object {
        private const val TAG = "ScreenVideoEncoder"
    }

    private val mimeType = if (useHevc) "video/hevc" else "video/avc"

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoTrackIndex: Int = -1

    private var encodingJob: Job? = null
    @Volatile
    private var isRunning = false
    @Volatile
    private var isPaused = false

    private val bufferInfo = MediaCodec.BufferInfo()
    private var firstVideoPtsUs: Long = -1L
    private var lastVideoPtsUs: Long = -1L

    fun prepare() {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1s keyframe
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

            // TAREFA 1: Desativar B-Frames e forçar baixa latência (ordem de decodificação = exibição)
            if (!useHevc) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            setInteger(MediaFormat.KEY_LATENCY, 0)
        }

        val codec = MediaCodec.createEncoderByType(mimeType)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        // TAREFA 1: Requisitar Keyframe Imediato no Início da Gravação
        val syncBundle = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
        codec.setParameters(syncBundle)

        mediaCodec = codec
        inputSurface = surface

        // TAREFA 2: Criação do VirtualDisplay ("DexRecorder", VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "DexRecorder",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        isRunning = true
        Log.d(TAG, "ScreenVideoEncoder preparado: ${width}x${height} @ ${fps}fps, bitrate=$bitrate, mime=$mimeType, flag=AUTO_MIRROR")
    }

    /**
     * Inicia a drenagem dos frames de vídeo em um escopo independente (Dispatchers.IO).
     */
    fun startEncoding(scope: CoroutineScope) {
        encodingJob = scope.launch(Dispatchers.IO) {
            drainLoop()
        }
    }

    private fun drainLoop() {
        val codec = mediaCodec ?: return
        Log.d(TAG, "Loop de drenagem de vídeo iniciado em thread dedicada: ${Thread.currentThread().name}")

        while (isRunning && !Thread.currentThread().isInterrupted) {
            if (isPaused) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            val outputBufferIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao chamar dequeueOutputBuffer no encoder de vídeo", e)
                break
            }

            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Sem frame de saída pronto neste ciclo
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    Log.d(TAG, "Formato do vídeo alterado (INFO_OUTPUT_FORMAT_CHANGED): $newFormat")
                    videoTrackIndex = muxerManager.addVideoTrack(newFormat)
                }
                outputBufferIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        if (!isConfig && !isPaused) {
                            // TAREFA 1: Alinhamento do Primeiro Frame baseado no primeiro I-Frame com BUFFER_FLAG_KEY_FRAME
                            if (firstVideoPtsUs < 0L) {
                                if (isKeyFrame) {
                                    firstVideoPtsUs = bufferInfo.presentationTimeUs
                                    Log.d(TAG, "Primeiro I-Frame (Key Frame) capturado com sucesso. PTS inicial: $firstVideoPtsUs us")
                                } else {
                                    // Descarta frames intermediários até o primeiro I-Frame para evitar salto para 00:02 na Galeria
                                    codec.releaseOutputBuffer(outputBufferIndex, false)
                                    continue
                                }
                            }

                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val normalizedPts = (bufferInfo.presentationTimeUs - firstVideoPtsUs).coerceAtLeast(0L)
                            val finalPts = if (lastVideoPtsUs < 0L) {
                                normalizedPts
                            } else {
                                maxOf(normalizedPts, lastVideoPtsUs + 1L)
                            }
                            lastVideoPtsUs = finalPts
                            bufferInfo.presentationTimeUs = finalPts

                            // Escreve os frames no MediaMuxer sincronizado
                            muxerManager.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "Vídeo EOS recebido")
                        break
                    }
                }
            }
        }
        Log.d(TAG, "Loop de drenagem de vídeo finalizado.")
    }

    fun pause() {
        isPaused = true
        Log.d(TAG, "ScreenVideoEncoder pausado")
    }

    fun resume() {
        isPaused = false
        Log.d(TAG, "ScreenVideoEncoder retomado")
    }

    fun stop() {
        isRunning = false
        try {
            mediaCodec?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao sinalizar end-of-input-stream", e)
        }

        try {
            encodingJob?.cancel()
        } catch (_: Exception) {}

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao liberar virtualDisplay", e)
        } finally {
            virtualDisplay = null
        }

        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao liberar inputSurface", e)
        } finally {
            inputSurface = null
        }

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao liberar mediaCodec", e)
        } finally {
            mediaCodec = null
        }
    }
}
