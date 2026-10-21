package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import com.example.model.AudioCaptureMode
import com.example.video.MediaMuxerManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Pipeline completo e síncrono de Áudio:
 * AudioRecord (PCM 48.000 Hz Estéreo) -> MediaCodec (AAC LC 192 kbps) -> MediaMuxer sincronizado.
 *
 * Otimizações Anti-Chiado e Alta Fidelidade:
 * - TAREFA 1 (Apenas Áudio Interno): Leitura direta para dentro do ByteBuffer nativo do MediaCodec
 *   usando AudioRecord.READ_BLOCKING sem conversão intermediária para ShortArray/ByteArray (zero chiado).
 * - TAREFA 2 (Interno + Microfone / Microfone): Conversão explícita com ByteOrder.LITTLE_ENDIAN,
 *   Noise Gate no microfone para silenciar ruído de pré-amplificador e envio estrito de bytesRead.
 * - TAREFA 3 (Qualidade de Estúdio): Bitrate AAC elevado para 192 kbps (192_000 bps) e buffer de
 *   captura dimensionado com maxOf(minBufferSize * 4, 16384) para evitar underruns em 2K/4K 60-120fps.
 */
class AudioCaptureAndEncoder(
    private val context: Context,
    private val mediaProjection: MediaProjection?,
    private val audioMode: AudioCaptureMode,
    private val muxerManager: MediaMuxerManager,
    private val internalVolume: Float = 1.0f,
    private val micVolume: Float = 1.0f
) {
    companion object {
        private const val TAG = "AudioCaptureAndEncoder"

        // Constantes globais unificadas (48.000 Hz / Estéreo / 192 kbps)
        const val SAMPLE_RATE = 48000
        const val CHANNEL_COUNT = 2 // Estéreo nativo Samsung
        const val AUDIO_BIT_RATE = 192_000 // 192 kbps para eliminar distorção/chiado em jogos com alto alcance dinâmico

        // Cada frame estéreo PCM 16-bit: 2 canais * 2 bytes = 4 bytes
        private const val BYTES_PER_FRAME = CHANNEL_COUNT * 2
        private const val CHUNK_SIZE_BYTES = 4096 // 1024 frames estéreo (~21.3ms)
        private const val CHUNK_SIZE_SHORTS = CHUNK_SIZE_BYTES / 2 // 2048 amostras de 16-bits

        // Limiar do Noise Gate para o microfone (em escala PCM 16-bit: -32768..32767)
        // Corta o ruído branco / chiado térmico do microfone quando o usuário está em silêncio
        const val NOISE_GATE_THRESHOLD = 180

        private const val BLOCKED_AUDIO_CHECK_DURATION_MS = 5000L
        const val BLOCKED_AUDIO_WARNING_MESSAGE =
            "Aviso: Este aplicativo/jogo bloqueia a gravação de áudio interno pelo Android."

        /**
         * TAREFA 2: Soma as amostras de 16-bits (ShortArray) do áudio interno e do microfone
         * aplicando Noise Gate no microfone, ganho de volume e clamping entre Short.MIN_VALUE e Short.MAX_VALUE.
         */
        fun mixPcmShortBuffers(
            internalBuffer: ShortArray,
            internalShortsRead: Int,
            internalVol: Float,
            micBuffer: ShortArray,
            micShortsRead: Int,
            micVol: Float,
            outputShorts: ShortArray,
            sampleCount: Int,
            noiseGateThreshold: Int = NOISE_GATE_THRESHOLD
        ) {
            for (i in 0 until sampleCount) {
                val internalSample = if (i < internalShortsRead) {
                    (internalBuffer[i] * internalVol).toInt()
                } else {
                    0
                }

                val rawMicSample = if (i < micShortsRead) {
                    micBuffer[i].toInt()
                } else {
                    0
                }

                // Noise Gate: corta chiado de fundo do microfone abaixo do limiar
                val gatedMicSample = if (abs(rawMicSample) < noiseGateThreshold) {
                    0
                } else {
                    (rawMicSample * micVol).toInt()
                }

                val summed = (internalSample + gatedMicSample)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()

                outputShorts[i] = summed
            }
        }

        /**
         * Verifica se todos os bytes lidos no buffer PCM (ByteArray) são exatamente 0.
         */
        fun isBufferAllZeros(buffer: ByteArray, bytesRead: Int): Boolean {
            if (bytesRead <= 0) return true
            for (i in 0 until bytesRead) {
                if (buffer[i].toInt() != 0) return false
            }
            return true
        }

        /**
         * Verifica se todos os bytes lidos diretamente no ByteBuffer nativo são exatamente 0,
         * sem alterar position/limit nem modificar os dados do buffer.
         */
        fun isDirectBufferAllZeros(buffer: ByteBuffer, bytesRead: Int): Boolean {
            if (bytesRead <= 0) return true
            val limit = minOf(bytesRead, buffer.limit())
            for (i in 0 until limit) {
                if (buffer.get(i).toInt() != 0) return false
            }
            return true
        }
    }

    private var audioCodec: MediaCodec? = null
    private var internalAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null

    private var audioTrackIndex: Int = -1
    private val bufferInfo = MediaCodec.BufferInfo()

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private var captureThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Cálculo estritamente linear do PTS de áudio
    private var totalAudioBytesRead = 0L

    /**
     * Inicializa o codificador MediaCodec AAC com os parâmetros exatos (48.000 Hz, Estéreo, 192 kbps).
     */
    fun prepare() {
        if (audioMode == AudioCaptureMode.MUTE) {
            Log.d(TAG, "Modo MUTE selecionado. Codificador de áudio não será instanciado.")
            return
        }

        try {
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec

            Log.d(TAG, "MediaCodec AAC inicializado com sucesso: 48000Hz, Estéreo, 192kbps")

            // Inicializa os AudioRecords separados de acordo com o modo selecionado
            initAudioRecords()

            // Injeta um buffer inicial de silêncio para forçar a geração de INFO_OUTPUT_FORMAT_CHANGED
            // garantindo que o MediaMuxer registre a faixa de áudio imediatamente no início da gravação
            primeCodecFormat()
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao preparar AudioCaptureAndEncoder", e)
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecords() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // TAREFA 1: Aumenta o buffer do AudioRecord para evitar engasgos/underruns em 2K/4K 60fps
        val bufferSize = maxOf(minBufferSize * 4, 16384)

        // 1. Inicializa internalAudioRecord (para INTERNAL_ONLY ou MIXED_INTERNAL_MIC)
        if (audioMode == AudioCaptureMode.INTERNAL_ONLY || audioMode == AudioCaptureMode.MIXED_INTERNAL_MIC) {
            if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .build()

                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()

                    val record = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(config)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize)
                        .build()

                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        internalAudioRecord = record
                        Log.d(TAG, "internalAudioRecord inicializado: 48000Hz, Estéreo, PCM 16-bit, buffer=$bufferSize")
                    } else {
                        Log.e(TAG, "internalAudioRecord state != STATE_INITIALIZED (${record.state})")
                        record.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao instanciar internalAudioRecord com AudioPlaybackCapture", e)
                }
            } else {
                Log.w(TAG, "mediaProjection nulo ou Android < 10. Impossível capturar áudio interno.")
            }
        }

        // 2. Inicializa micAudioRecord (para MIC_ONLY ou MIXED_INTERNAL_MIC)
        if (audioMode == AudioCaptureMode.MIC_ONLY || audioMode == AudioCaptureMode.MIXED_INTERNAL_MIC) {
            micAudioRecord = createMicAudioRecord(bufferSize)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createMicAudioRecord(bufferSize: Int): AudioRecord? {
        // Usa fonte MIC limpa (sem VOICE_COMMUNICATION que ativa AGC agressivo e eleva o chiado de fundo)
        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "micAudioRecord inicializado: source=MIC, 48000Hz, Estéreo, PCM 16-bit, buffer=$bufferSize")
                return record
            } else {
                record.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao inicializar micAudioRecord com source=MIC", e)
        }
        Log.e(TAG, "Não foi possível inicializar micAudioRecord.")
        return null
    }

    /**
     * Envia um pequeno bloco de silêncio para obter INFO_OUTPUT_FORMAT_CHANGED de imediato,
     * adicionando a trilha no MediaMuxer sem esperar por reprodução de mídia no dispositivo.
     */
    private fun primeCodecFormat() {
        val codec = audioCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                val silence = ByteArray(CHUNK_SIZE_BYTES)
                inputBuffer?.put(silence)

                val audioPtsUs = (totalAudioBytesRead * 1_000_000L) / (SAMPLE_RATE * CHANNEL_COUNT * 2L)
                totalAudioBytesRead += silence.size

                codec.queueInputBuffer(inputIndex, 0, silence.size, audioPtsUs, 0)
            }
            drainOutput(endOfStream = false)
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao enviar prime buffer para o codec de áudio", e)
        }
    }

    fun start() {
        if (audioMode == AudioCaptureMode.MUTE) return

        totalAudioBytesRead = 0L

        try {
            if (internalAudioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                internalAudioRecord?.startRecording()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar internalAudioRecord", e)
        }

        try {
            if (micAudioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                micAudioRecord?.startRecording()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar micAudioRecord", e)
        }

        isRecording.set(true)
        isPaused.set(false)

        captureThread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}

            runAudioPipelineLoop()
        }, "DexAudio-PipelineThread").apply {
            start()
        }

        Log.d(TAG, "Thread de pipeline de áudio iniciada com sucesso.")
    }

    /**
     * Loop principal de captura e codificação:
     * - Em INTERNAL_ONLY: leitura direta ByteBuffer com AudioRecord.READ_BLOCKING sem conversão para ShortArray.
     * - Em MIXED_INTERNAL_MIC e MIC_ONLY: leitura ShortArray com READ_BLOCKING, Noise Gate e ByteOrder.LITTLE_ENDIAN.
     */
    private fun runAudioPipelineLoop() {
        val internalRecord = internalAudioRecord
        val micRecord = micAudioRecord

        // Buffers de 16-bits (ShortArray) usados apenas quando há Microfone ativo (MIC_ONLY / MIXED_INTERNAL_MIC)
        val shortBufferInternal = ShortArray(CHUNK_SIZE_SHORTS)
        val shortBufferMic = ShortArray(CHUNK_SIZE_SHORTS)
        val mixedShorts = ShortArray(CHUNK_SIZE_SHORTS)

        // Variáveis de controle para detecção de áudio bloqueado nos primeiros 5 segundos
        var activeCheckDurationMs = 0L
        var lastCheckTimestampMs = System.currentTimeMillis()
        var allZeroInFirst5Seconds = true
        var hasReadAnyInternalBuffer = false
        var silenceCheckCompleted = false

        while (isRecording.get() && !Thread.currentThread().isInterrupted) {
            if (isPaused.get()) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
                lastCheckTimestampMs = System.currentTimeMillis()
                continue
            }

            when (audioMode) {
                AudioCaptureMode.INTERNAL_ONLY -> {
                    val record = internalRecord
                    val codec = audioCodec
                    if (record == null || codec == null) {
                        drainOutput(endOfStream = false)
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }

                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try {
                            record.startRecording()
                        } catch (e: Exception) {
                            Log.e("DexRecorderAudio", "Falha ao iniciar AudioRecord: ${e.message}")
                            drainOutput(endOfStream = false)
                            try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                            continue
                        }
                    }

                    // TAREFA 1: Leitura Direta ByteBuffer sem Conversão no Modo "Apenas Áudio Interno"
                    val inputBufferIndex = try {
                        codec.dequeueInputBuffer(10_000)
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro em dequeueInputBuffer no modo INTERNAL_ONLY", e)
                        -1
                    }

                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            // Leitura direta da memória nativa sem conversão intermediária (zero chiado)
                            val bytesRead = record.read(
                                inputBuffer,
                                inputBuffer.capacity(),
                                AudioRecord.READ_BLOCKING
                            )

                            if (bytesRead > 0) {
                                // Detector de Áudio Bloqueado pelo Jogo nos primeiros 5 segundos
                                if (!silenceCheckCompleted) {
                                    val now = System.currentTimeMillis()
                                    activeCheckDurationMs += (now - lastCheckTimestampMs).coerceAtLeast(0L)
                                    lastCheckTimestampMs = now
                                    hasReadAnyInternalBuffer = true

                                    if (allZeroInFirst5Seconds && !isDirectBufferAllZeros(inputBuffer, bytesRead)) {
                                        allZeroInFirst5Seconds = false
                                        silenceCheckCompleted = true
                                    }

                                    if (!silenceCheckCompleted && activeCheckDurationMs >= BLOCKED_AUDIO_CHECK_DURATION_MS) {
                                        silenceCheckCompleted = true
                                        if (allZeroInFirst5Seconds && hasReadAnyInternalBuffer) {
                                            Log.w(TAG, "Detectado buffer PCM 100% zerado nos primeiros 5s. Exibindo aviso de bloqueio DRM/AudioPlaybackCapture.")
                                            notifyBlockedInternalAudio()
                                        }
                                    }
                                }

                                // Se o volume interno estiver abaixo de 100%, aplica ganho respeitando ByteOrder.LITTLE_ENDIAN;
                                // em 100% (1.0f), não toca nos bytes do ByteBuffer nativo.
                                if (internalVolume < 0.999f) {
                                    applyVolumeToDirectBuffer(inputBuffer, bytesRead, internalVolume)
                                }

                                val presentationTimeUs = (totalAudioBytesRead * 1_000_000L) / (48000L * 2 * 2)
                                totalAudioBytesRead += bytesRead
                                codec.queueInputBuffer(inputBufferIndex, 0, bytesRead, presentationTimeUs, 0)
                            } else {
                                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0)
                            }
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0)
                        }
                    }

                    drainOutput(endOfStream = false)
                }

                AudioCaptureMode.MIC_ONLY -> {
                    val record = micRecord
                    if (record == null) {
                        drainOutput(endOfStream = false)
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }

                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try {
                            record.startRecording()
                        } catch (e: Exception) {
                            Log.e("DexRecorderAudio", "Falha ao iniciar Mic AudioRecord: ${e.message}")
                            drainOutput(endOfStream = false)
                            try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                            continue
                        }
                    }

                    val shortsRead = record.read(shortBufferMic, 0, shortBufferMic.size, AudioRecord.READ_BLOCKING)
                    if (shortsRead <= 0) {
                        Log.e("DexRecorderAudio", "Falha na leitura do Mic AudioRecord. Código: $shortsRead")
                        drainOutput(endOfStream = false)
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }

                    val alignedShorts = (shortsRead / CHANNEL_COUNT) * CHANNEL_COUNT
                    for (i in 0 until alignedShorts) {
                        val rawSample = shortBufferMic[i].toInt()
                        val gated = if (abs(rawSample) < NOISE_GATE_THRESHOLD) {
                            0
                        } else {
                            (rawSample * micVolume).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        }
                        mixedShorts[i] = gated.toShort()
                    }

                    if (alignedShorts > 0) {
                        feedCodecShortBufferLittleEndian(mixedShorts, alignedShorts)
                        drainOutput(endOfStream = false)
                    }
                }

                AudioCaptureMode.MIXED_INTERNAL_MIC -> {
                    // TAREFA 2: Caminho com ByteOrder.LITTLE_ENDIAN + Noise Gate para "Interno + Microfone"
                    val intRec = internalRecord
                    val mRec = micRecord

                    if (intRec != null && intRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try { intRec.startRecording() } catch (e: Exception) {
                            Log.e("DexRecorderAudio", "Falha ao iniciar internalAudioRecord: ${e.message}")
                        }
                    }
                    if (mRec != null && mRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try { mRec.startRecording() } catch (e: Exception) {
                            Log.e("DexRecorderAudio", "Falha ao iniciar micAudioRecord: ${e.message}")
                        }
                    }

                    val intShortsRead = intRec?.read(
                        shortBufferInternal,
                        0,
                        shortBufferInternal.size,
                        AudioRecord.READ_BLOCKING
                    ) ?: -1
                    val micShortsRead = mRec?.read(
                        shortBufferMic,
                        0,
                        shortBufferMic.size,
                        AudioRecord.READ_BLOCKING
                    ) ?: -1

                    if (intShortsRead <= 0 && micShortsRead <= 0) {
                        Log.e("DexRecorderAudio", "Falha na leitura de ambos AudioRecords. int=$intShortsRead, mic=$micShortsRead")
                        drainOutput(endOfStream = false)
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }

                    // Determina o número exato de amostras de 16-bits lidas (alinhado ao par estéreo)
                    val rawSamplesCount = maxOf(
                        if (intShortsRead > 0) intShortsRead else 0,
                        if (micShortsRead > 0) micShortsRead else 0
                    )
                    val alignedSamplesCount = (rawSamplesCount / CHANNEL_COUNT) * CHANNEL_COUNT

                    if (alignedSamplesCount > 0) {
                        mixPcmShortBuffers(
                            internalBuffer = shortBufferInternal,
                            internalShortsRead = if (intShortsRead > 0) intShortsRead else 0,
                            internalVol = internalVolume,
                            micBuffer = shortBufferMic,
                            micShortsRead = if (micShortsRead > 0) micShortsRead else 0,
                            micVol = micVolume,
                            outputShorts = mixedShorts,
                            sampleCount = alignedSamplesCount
                        )

                        // Escreve no MediaCodec usando ByteOrder.LITTLE_ENDIAN explícito e apenas as amostras lidas
                        feedCodecShortBufferLittleEndian(mixedShorts, alignedSamplesCount)
                        drainOutput(endOfStream = false)
                    }
                }

                AudioCaptureMode.MUTE -> break
            }
        }

        Log.d(TAG, "Loop de áudio encerrado.")
    }

    /**
     * Exibe o Toast na Main Thread caso todos os bytes nos primeiros 5 segundos sejam zero.
     */
    private fun notifyBlockedInternalAudio() {
        mainHandler.post {
            Toast.makeText(
                context.applicationContext,
                BLOCKED_AUDIO_WARNING_MESSAGE,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * TAREFA 2: Alimenta o inputBuffer do MediaCodec a partir de um ShortArray garantindo
     * estritamente ByteOrder.LITTLE_ENDIAN e enfileirando apenas os bytes válidos (shortCount * 2).
     */
    private fun feedCodecShortBufferLittleEndian(shorts: ShortArray, shortCount: Int) {
        val codec = audioCodec ?: return
        try {
            val inputBufferIndex = codec.dequeueInputBuffer(10_000L)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                    val maxShorts = minOf(shortCount, inputBuffer.capacity() / 2)
                    val shortBuffer = inputBuffer.asShortBuffer()
                    shortBuffer.put(shorts, 0, maxShorts)

                    val bytesWritten = maxShorts * 2
                    val presentationTimeUs = (totalAudioBytesRead * 1_000_000L) / (48000L * 2 * 2)
                    totalAudioBytesRead += bytesWritten

                    codec.queueInputBuffer(inputBufferIndex, 0, bytesWritten, presentationTimeUs, 0)
                } else {
                    codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao alimentar entrada Little-Endian do codec de áudio", e)
        }
    }

    /**
     * Aplica volume em um ByteBuffer direto respeitando ByteOrder.LITTLE_ENDIAN (usado apenas se internalVolume < 1.0f).
     */
    private fun applyVolumeToDirectBuffer(buffer: ByteBuffer, bytesRead: Int, volume: Float) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = bytesRead / 2
        for (i in 0 until sampleCount) {
            val byteIndex = i * 2
            val sample = buffer.getShort(byteIndex)
            val scaled = (sample * volume).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            buffer.putShort(byteIndex, scaled)
        }
    }

    /**
     * Drenar Saída (Output Buffer) do MediaCodec AAC para o MediaMuxer
     */
    private fun drainOutput(endOfStream: Boolean) {
        val codec = audioCodec ?: return
        var tryAgainCount = 0
        val maxTryAgain = if (endOfStream) 8 else 1
        while (true) {
            val outputIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (e: Exception) {
                Log.w(TAG, "Exceção ao obter buffer de saída do codec de áudio", e)
                break
            }

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    tryAgainCount++
                    if (!endOfStream || tryAgainCount >= maxTryAgain) break
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    tryAgainCount = 0
                    val newFormat = codec.outputFormat
                    Log.d(TAG, "Formato do codec de áudio alterado (INFO_OUTPUT_FORMAT_CHANGED): $newFormat")
                    audioTrackIndex = muxerManager.addAudioTrack(newFormat)
                }
                outputIndex >= 0 -> {
                    tryAgainCount = 0
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            synchronized(muxerManager.muxerLock) {
                                if (muxerManager.isMuxerStarted && audioTrackIndex >= 0) {
                                    muxerManager.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "Áudio EOS alcançado com sucesso")
                        break
                    }
                }
            }
        }
    }

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
    }

    /**
     * Envia o sinal de final de fluxo (BUFFER_FLAG_END_OF_STREAM) para a entrada do codec de áudio
     * para que todos os pacotes pendentes sejam descarregados no MediaMuxer antes de mediaMuxer.stop().
     */
    fun stop() {
        if (!isRecording.getAndSet(false)) return

        try {
            captureThread?.interrupt()
            captureThread?.join(500)
        } catch (_: Exception) {}

        try {
            internalAudioRecord?.stop()
            internalAudioRecord?.release()
            internalAudioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Exceção ao parar internalAudioRecord", e)
        }

        try {
            micAudioRecord?.stop()
            micAudioRecord?.release()
            micAudioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Exceção ao parar micAudioRecord", e)
        }

        val codec = audioCodec
        if (codec != null) {
            try {
                var eosQueued = false
                val inputIndex = codec.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val audioPtsUs = (totalAudioBytesRead * 1_000_000L) / (SAMPLE_RATE * CHANNEL_COUNT * 2L)
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        audioPtsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    eosQueued = true
                }

                drainOutput(endOfStream = eosQueued)

                codec.stop()
                codec.release()
                Log.d(TAG, "Codificador de áudio finalizado com EOS e liberado.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao encerrar MediaCodec de áudio", e)
            } finally {
                audioCodec = null
            }
        }
    }
}

