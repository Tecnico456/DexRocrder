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

        /**
         * Garante que a dimensão seja múltiplo de 16 (requisito de alinhamento de macroblocos H.264/HEVC para 2K/QHD).
         */
        fun alignTo16(dimension: Int): Int {
            val aligned = (dimension / 16) * 16
            return if (aligned >= 16) aligned else 16
        }

        /**
         * TAREFA 3: Cálculo Dinâmico de Bitrate proporcional à resolução e ao FPS.
         * Para 2K (2560x1440), 4K (3840x2160) ou altas taxas de quadros (90/120 FPS),
         * escala o bitrate na faixa de 16 Mbps a 40 Mbps.
         */
        fun calculateDynamicBitrate(
            width: Int,
            height: Int,
            fps: Int,
            baseBitrateBps: Int = 12_000_000
        ): Int {
            val pixels = width.toLong() * height.toLong()
            val refPixels1080p = 1920L * 1080L // 2.073.600 pixels
            val is2KOrHigher = pixels >= (2560L * 1440L)
            val is4K = pixels >= (3840L * 2160L)
            val isHighFps = fps >= 90

            val pixelFactor = (pixels.toDouble() / refPixels1080p.toDouble()).coerceAtLeast(0.45)
            val fpsFactor = (fps.toDouble() / 60.0).coerceAtLeast(0.6)
            val scaledBitrate = (baseBitrateBps * pixelFactor * fpsFactor).toInt()

            return if (is2KOrHigher || isHighFps) {
                val minRecommended = when {
                    is4K && fps >= 90 -> 36_000_000
                    is4K -> 28_000_000
                    is2KOrHigher && fps >= 120 -> 28_000_000
                    is2KOrHigher && fps >= 90 -> 24_000_000
                    is2KOrHigher -> 20_000_000
                    fps >= 120 -> 20_000_000
                    else -> 16_000_000 // 90 FPS em 720p/1080p
                }
                maxOf(scaledBitrate, minRecommended).coerceIn(16_000_000, 40_000_000)
            } else {
                scaledBitrate.coerceIn(4_000_000, 40_000_000)
            }
        }
    }

    private val mimeType = if (useHevc) "video/hevc" else "video/avc"

    var actualWidth: Int = width
        private set
    var actualHeight: Int = height
        private set
    var actualFps: Int = fps
        private set
    var actualBitrate: Int = bitrate
        private set

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
        val videoCodec = MediaCodec.createEncoderByType(mimeType)
        val codecInfo = videoCodec.codecInfo
        val videoCapabilities = try {
            codecInfo.getCapabilitiesForType(mimeType)?.videoCapabilities
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível consultar VideoCapabilities para $mimeType", e)
            null
        }

        var targetWidth = if (width % 2 == 0) width else width - 1
        var targetHeight = if (height % 2 == 0) height else height - 1
        var selectedFps = fps

        // TAREFA 3: Validação de Suporte de Hardware (VideoCapabilities) antes de videoCodec.configure()
        if (videoCapabilities != null) {
            // Se a resolução não for suportada diretamente, tenta alinhar para múltiplos de 16 ou limitar ao range do hardware
            if (!videoCapabilities.isSizeSupported(targetWidth, targetHeight)) {
                val alignedW = alignTo16(targetWidth).coerceIn(
                    videoCapabilities.supportedWidths.lower,
                    videoCapabilities.supportedWidths.upper
                )
                val alignedH = alignTo16(targetHeight).coerceIn(
                    videoCapabilities.supportedHeights.lower,
                    videoCapabilities.supportedHeights.upper
                )
                if (videoCapabilities.isSizeSupported(alignedW, alignedH)) {
                    targetWidth = alignedW
                    targetHeight = alignedH
                } else {
                    // Fallback seguro para 1080p na orientação correspondente
                    val isLandscape = targetWidth >= targetHeight
                    targetWidth = if (isLandscape) 1920 else 1080
                    targetHeight = if (isLandscape) 1080 else 1920
                }
                Log.w(TAG, "Resolução ajustada para compatibilidade com o hardware: ${targetWidth}x${targetHeight}")
            }

            // Verifica se o encoder suporta a combinação escolhida (ex: 4K @ 120 FPS ou 2K @ 120 FPS)
            if (!videoCapabilities.areSizeAndRateSupported(targetWidth, targetHeight, selectedFps.toDouble())) {
                val maxSupportedRate = try {
                    videoCapabilities.getSupportedFrameRatesFor(targetWidth, targetHeight).upper.toInt()
                } catch (e: Exception) {
                    60
                }

                // Tenta reduzir para o maior FPS suportado (90 -> 60 -> 30)
                val fallbackCandidates = listOf(90, 60, 30).filter { it <= maxSupportedRate }
                val supportedCandidate = fallbackCandidates.firstOrNull { candidateFps ->
                    videoCapabilities.areSizeAndRateSupported(targetWidth, targetHeight, candidateFps.toDouble())
                }
                selectedFps = supportedCandidate ?: maxSupportedRate.coerceIn(24, 60)
                Log.w(
                    TAG,
                    "Combinação ${targetWidth}x${targetHeight} @ ${fps} FPS não suportada pelo hardware. " +
                        "Reduzindo automaticamente para ${selectedFps} FPS."
                )
            }
        }

        // TAREFA 3: Cálculo Dinâmico de Bitrate escalado por resolução e FPS, respeitando videoCapabilities.bitrateRange
        var targetBitrate = calculateDynamicBitrate(targetWidth, targetHeight, selectedFps, bitrate)
        if (videoCapabilities != null) {
            targetBitrate = videoCapabilities.bitrateRange.clamp(targetBitrate)
        }

        actualWidth = targetWidth
        actualHeight = targetHeight
        actualFps = selectedFps
        actualBitrate = targetBitrate

        val mediaFormat = MediaFormat.createVideoFormat(mimeType, targetWidth, targetHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, selectedFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1s keyframe
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

            // TAREFA 3: Configuração de Alta Taxa de Quadros em tempo real no MediaFormat
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Prioridade máxima em tempo real
            setFloat(MediaFormat.KEY_OPERATING_RATE, selectedFps.toFloat())

            // TAREFA 1: Desativar B-Frames e forçar baixa latência (ordem de decodificação = exibição)
            // Para resoluções > 1080p ou FPS >= 90, o perfil Baseline pode ter limite de macroblocos em alguns SoCs,
            // portanto aplicamos Baseline apenas até 1080p @ 60fps ou fazemos fallback seguro no configure().
            if (!useHevc && targetWidth * targetHeight <= 1920 * 1080 && selectedFps <= 60) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }
            setInteger(MediaFormat.KEY_LATENCY, 0)
        }

        try {
            videoCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: MediaCodec.CodecException) {
            Log.w(TAG, "CodecException na configuração inicial (${targetWidth}x${targetHeight} @ ${selectedFps}fps). Aplicando fallback seguro @ 60 FPS...", e)
            videoCodec.reset()
            selectedFps = minOf(selectedFps, 60)
            actualFps = selectedFps
            val fallbackBitrate = videoCapabilities?.bitrateRange?.clamp(
                calculateDynamicBitrate(targetWidth, targetHeight, selectedFps, bitrate)
            ) ?: 16_000_000
            actualBitrate = fallbackBitrate

            val fallbackFormat = MediaFormat.createVideoFormat(mimeType, targetWidth, targetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, fallbackBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, selectedFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setFloat(MediaFormat.KEY_OPERATING_RATE, selectedFps.toFloat())
            }
            videoCodec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val surface = videoCodec.createInputSurface()
        videoCodec.start()

        // TAREFA 1: Requisitar Keyframe Imediato no Início da Gravação
        val syncBundle = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
        videoCodec.setParameters(syncBundle)

        mediaCodec = videoCodec
        inputSurface = surface

        // TAREFA 2: Criação do VirtualDisplay ("DexRecorder", VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "DexRecorder",
            targetWidth,
            targetHeight,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        isRunning = true
        Log.d(
            TAG,
            "ScreenVideoEncoder preparado: ${targetWidth}x${targetHeight} @ ${selectedFps}fps " +
                "(solicitado=${fps}fps), bitrate=$targetBitrate, mime=$mimeType, flag=AUTO_MIRROR"
        )
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
