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

/**
 * Pipeline completo e síncrono de Áudio:
 * AudioRecord (PCM 48.000 Hz Estéreo) -> MediaCodec (AAC LC 128 kbps) -> MediaMuxer sincronizado.
 *
 * Inclui:
 * - TAREFA 1: Expansão de todos os atributos de captura permitidos no AudioPlaybackCaptureConfiguration.
 * - TAREFA 2: Audio Mixer Real (ShortArray 16-bit) somando Áudio Interno + Microfone simultâneos com clamping.
 * - TAREFA 3: Detector de Áudio Bloqueado pelo Jogo nos primeiros 5 segundos com aviso via Toast na Main Thread.
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

        // Constantes globais unificadas (48.000 Hz / Estéreo)
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 2 // Estéreo nativo Samsung
        private const val AUDIO_BIT_RATE = 128000

        // Cada frame estéreo PCM 16-bit: 2 canais * 2 bytes = 4 bytes
        private const val BYTES_PER_FRAME = CHANNEL_COUNT * 2
        private const val CHUNK_SIZE_BYTES = 4096 // 1024 frames estéreo (~21.3ms)
        private const val CHUNK_SIZE_SHORTS = CHUNK_SIZE_BYTES / 2 // 2048 amostras de 16-bits

        private const val BLOCKED_AUDIO_CHECK_DURATION_MS = 5000L
        const val BLOCKED_AUDIO_WARNING_MESSAGE =
            "Aviso: Este aplicativo/jogo bloqueia a gravação de áudio interno pelo Android."

        /**
         * TAREFA 2: Soma as amostras de 16-bits (ShortArray) do áudio interno e do microfone
         * aplicando ganho de volume e clamping entre Short.MIN_VALUE e Short.MAX_VALUE.
         */
        fun mixPcmShortBuffers(
            internalBuffer: ShortArray,
            internalShortsRead: Int,
            internalVol: Float,
            micBuffer: ShortArray,
            micShortsRead: Int,
            micVol: Float,
            outputShorts: ShortArray,
            sampleCount: Int
        ) {
            for (i in 0 until sampleCount) {
                val internalSample = if (i < internalShortsRead) {
                    (internalBuffer[i] * internalVol).toInt()
                } else {
                    0
                }

                val micSample = if (i < micShortsRead) {
                    (micBuffer[i] * micVol).toInt()
                } else {
                    0
                }

                val summed = (internalSample + micSample)
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()

                outputShorts[i] = summed
            }
        }

        /**
         * TAREFA 3: Verifica se todos os bytes lidos no buffer PCM são exatamente 0.
         */
        fun isBufferAllZeros(buffer: ByteArray, bytesRead: Int): Boolean {
            if (bytesRead <= 0) return true
            for (i in 0 until bytesRead) {
                if (buffer[i].toInt() != 0) return false
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
     * Inicializa o codificador MediaCodec AAC com os parâmetros exatos.
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

            Log.d(TAG, "MediaCodec AAC inicializado com sucesso: 48000Hz, Estéreo, 128kbps")

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
        val bufferSize = maxOf(minBufferSize * 4, 32768)

        // 1. Inicializa internalAudioRecord (para INTERNAL_ONLY ou MIXED_INTERNAL_MIC)
        if (audioMode == AudioCaptureMode.INTERNAL_ONLY || audioMode == AudioCaptureMode.MIXED_INTERNAL_MIC) {
            if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // TAREFA 1: Expandir Todos os Atributos de Captura Permitidos para motores de jogos (AAudio/OpenSL ES)
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
                        Log.d(TAG, "internalAudioRecord inicializado: 48000Hz, Estéreo, PCM 16-bit (5 atributos de uso ativos)")
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
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )
        for (source in sources) {
            try {
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "micAudioRecord inicializado: source=$source, 48000Hz, Estéreo, PCM 16-bit")
                    return record
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao tentar inicializar micAudioRecord com source=$source", e)
            }
        }
        Log.e(TAG, "Não foi possível inicializar micAudioRecord em nenhuma fonte de áudio.")
        return null
    }

    /**
     * Envia um pequeno bloco de silêncio para obter INFO_OUTPUT_FORMAT_CHANGED de imediato,
     * adicionando a trilha no MediaMuxer sem esperar por reprodução de mídia no dispositivo.
     */
    private fun primeCodecFormat() {
        val codec = audioCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(10000L)
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
     * Loop principal de leitura, detecção de bloqueio de áudio (DRM) e mixagem PCM 16-bit (ShortArray).
     */
    private fun runAudioPipelineLoop() {
        val internalRecord = internalAudioRecord
        val micRecord = micAudioRecord

        // Buffers de 16-bits (ShortArray) e ByteArray para alimentação do MediaCodec AAC
        val readBufferInternalBytes = ByteArray(CHUNK_SIZE_BYTES)
        val shortBufferInternal = ShortArray(CHUNK_SIZE_SHORTS)
        val shortBufferMic = ShortArray(CHUNK_SIZE_SHORTS)
        val mixedShorts = ShortArray(CHUNK_SIZE_SHORTS)
        val pcmOutputBytes = ByteArray(CHUNK_SIZE_BYTES)

        // TAREFA 3: Variáveis de controle para detecção de áudio bloqueado nos primeiros 5 segundos
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
                    if (record == null) {
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

                    val bytesRead = record.read(readBufferInternalBytes, 0, readBufferInternalBytes.size)
                    if (bytesRead <= 0) {
                        Log.e("DexRecorderAudio", "Falha na leitura do AudioRecord. Código: $bytesRead")
                        drainOutput(endOfStream = false)
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }

                    // TAREFA 3: Detector de Áudio Bloqueado pelo Jogo nos primeiros 5 segundos
                    if (!silenceCheckCompleted) {
                        val now = System.currentTimeMillis()
                        activeCheckDurationMs += (now - lastCheckTimestampMs).coerceAtLeast(0L)
                        lastCheckTimestampMs = now
                        hasReadAnyInternalBuffer = true

                        if (allZeroInFirst5Seconds && !isBufferAllZeros(readBufferInternalBytes, bytesRead)) {
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

                    applyVolume(readBufferInternalBytes, bytesRead, internalVolume)
                    feedCodecInput(readBufferInternalBytes, bytesRead)
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

                    val shortsRead = record.read(shortBufferMic, 0, shortBufferMic.size)
                    if (shortsRead <= 0) {
                        Log.e("DexRecorderAudio", "Falha na leitura do Mic AudioRecord. Código: $shortsRead")
                        drainOutput(endOfStream = false)
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }

                    val alignedShorts = (shortsRead / CHANNEL_COUNT) * CHANNEL_COUNT
                    for (i in 0 until alignedShorts) {
                        val scaled = (shortBufferMic[i] * micVolume).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                        val byteIdx = i * 2
                        pcmOutputBytes[byteIdx] = (scaled.toInt() and 0xFF).toByte()
                        pcmOutputBytes[byteIdx + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
                    }

                    val bytesToFeed = alignedShorts * 2
                    if (bytesToFeed > 0) {
                        feedCodecInput(pcmOutputBytes, bytesToFeed)
                        drainOutput(endOfStream = false)
                    }
                }

                AudioCaptureMode.MIXED_INTERNAL_MIC -> {
                    // TAREFA 2: Audio Mixer Real (Áudio Interno + Microfone Simultâneos em ShortArray 16-bits)
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

                    val intShortsRead = intRec?.read(shortBufferInternal, 0, shortBufferInternal.size) ?: -1
                    val micShortsRead = mRec?.read(shortBufferMic, 0, shortBufferMic.size) ?: -1

                    if (intShortsRead <= 0 && micShortsRead <= 0) {
                        Log.e("DexRecorderAudio", "Falha na leitura de ambos AudioRecords. int=$intShortsRead, mic=$micShortsRead")
                        drainOutput(endOfStream = false)
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }

                    // Determina o número de amostras de 16-bits lidas (alinhado ao par estéreo)
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

                        // Converte o ShortArray mixado para ByteArray Little-Endian (PCM 16-bit)
                        for (i in 0 until alignedSamplesCount) {
                            val sample = mixedShorts[i].toInt()
                            val byteIdx = i * 2
                            pcmOutputBytes[byteIdx] = (sample and 0xFF).toByte()
                            pcmOutputBytes[byteIdx + 1] = ((sample shr 8) and 0xFF).toByte()
                        }

                        val bytesToFeed = alignedSamplesCount * 2
                        feedCodecInput(pcmOutputBytes, bytesToFeed)
                        drainOutput(endOfStream = false)
                    }
                }

                AudioCaptureMode.MUTE -> break
            }
        }

        Log.d(TAG, "Loop de áudio encerrado.")
    }

    /**
     * TAREFA 3: Exibe o Toast na Main Thread caso todos os bytes nos primeiros 5 segundos sejam zero.
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
     * Alimentar Entrada (Input Buffer) do MediaCodec AAC
     */
    private fun feedCodecInput(buffer: ByteArray, bytesRead: Int) {
        val codec = audioCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(10000L)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(buffer, 0, bytesRead)

                val audioPtsUs = (totalAudioBytesRead * 1_000_000L) / (SAMPLE_RATE * CHANNEL_COUNT * 2L)
                totalAudioBytesRead += bytesRead

                codec.queueInputBuffer(inputIndex, 0, bytesRead, audioPtsUs, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao alimentar entrada do codec de áudio", e)
        }
    }

    /**
     * Drenar Saída (Output Buffer) do MediaCodec AAC para o MediaMuxer
     */
    private fun drainOutput(endOfStream: Boolean) {
        val codec = audioCodec ?: return
        while (true) {
            val outputIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10000L)
            } catch (e: Exception) {
                Log.w(TAG, "Exceção ao obter buffer de saída do codec de áudio", e)
                break
            }

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    Log.d(TAG, "Formato do codec de áudio alterado (INFO_OUTPUT_FORMAT_CHANGED): $newFormat")
                    audioTrackIndex = muxerManager.addAudioTrack(newFormat)
                }
                outputIndex >= 0 -> {
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
                val inputIndex = codec.dequeueInputBuffer(10000L)
                if (inputIndex >= 0) {
                    val audioPtsUs = (totalAudioBytesRead * 1_000_000L) / (SAMPLE_RATE * CHANNEL_COUNT * 2L)
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        audioPtsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }

                drainOutput(endOfStream = true)

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

    private fun applyVolume(buffer: ByteArray, length: Int, volume: Float) {
        if (volume == 1.0f) return
        val samples = length / 2
        for (i in 0 until samples) {
            val idx = i * 2
            val low = buffer[idx].toInt() and 0xFF
            val high = buffer[idx + 1].toInt()
            val sample = (high shl 8) or low
            val scaled = (sample * volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            buffer[idx] = (scaled.toInt() and 0xFF).toByte()
            buffer[idx + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
        }
    }
}
