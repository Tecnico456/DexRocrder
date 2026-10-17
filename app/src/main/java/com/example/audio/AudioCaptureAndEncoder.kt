package com.example.audio

import android.annotation.SuppressLint
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
import android.os.Process
import android.util.Log
import com.example.model.AudioCaptureMode
import com.example.video.MediaMuxerManager
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pipeline completo e síncrono de Áudio:
 * AudioRecord (PCM 48.000 Hz Estéreo) -> MediaCodec (AAC LC 128 kbps) -> MediaMuxer sincronizado.
 *
 * TAREFA 1: Correspondência Exata de Formatos (48.000 Hz / Estéreo)
 * TAREFA 2: Pipeline de Alimentação Entrada/Saída do Codec de Áudio (Alimentar Entrada e Drenar Saída)
 * TAREFA 3: Validação de Trilha no Encerramento com BUFFER_FLAG_END_OF_STREAM
 */
class AudioCaptureAndEncoder(
    private val mediaProjection: MediaProjection?,
    private val audioMode: AudioCaptureMode,
    private val muxerManager: MediaMuxerManager,
    private val internalVolume: Float = 1.0f,
    private val micVolume: Float = 1.0f
) {
    companion object {
        private const val TAG = "AudioCaptureAndEncoder"

        // TAREFA 1: Constantes globais unificadas
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_COUNT = 2 // Estéreo nativo Samsung
        private const val AUDIO_BIT_RATE = 128000

        // Cada frame estéreo PCM 16-bit: 2 canais * 2 bytes = 4 bytes
        private const val BYTES_PER_FRAME = CHANNEL_COUNT * 2
        private const val CHUNK_SIZE_BYTES = 4096 // 1024 frames estéreo (~21.3ms)
    }

    private var audioCodec: MediaCodec? = null
    private var internalAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null

    private var audioTrackIndex: Int = -1
    private val bufferInfo = MediaCodec.BufferInfo()

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private var captureThread: Thread? = null

    // Cálculo estritamente linear do PTS de áudio
    private var totalAudioBytesRead = 0L

    /**
     * TAREFA 1: Inicializa o codificador MediaCodec AAC com os parâmetros exatos.
     */
    fun prepare() {
        if (audioMode == AudioCaptureMode.MUTE) {
            Log.d(TAG, "Modo MUTE selecionado. Codificador de áudio não será instanciado.")
            return
        }

        try {
            // TAREFA 1: MediaFormat do Codificador de Áudio
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioCodec = codec

            Log.d(TAG, "MediaCodec AAC inicializado com sucesso: 48000Hz, Estéreo, 128kbps")

            // Inicializa os AudioRecords de acordo com o modo
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

        if (audioMode == AudioCaptureMode.INTERNAL_ONLY || audioMode == AudioCaptureMode.MIXED_INTERNAL_MIC) {
            if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // TAREFA 1: AudioPlaybackCaptureConfiguration com o mediaProjection ativo da tela
                    val audioPlaybackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()

                    val record = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(audioPlaybackConfig)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize)
                        .build()

                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        internalAudioRecord = record
                        Log.d(TAG, "Internal AudioRecord inicializado: 48000Hz, Estéreo, PCM 16-bit")
                    } else {
                        Log.e(TAG, "Internal AudioRecord state != STATE_INITIALIZED (${record.state})")
                        record.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao instanciar Internal AudioRecord com AudioPlaybackCapture", e)
                }
            } else {
                Log.w(TAG, "mediaProjection nulo ou Android < 10. Impossível capturar áudio interno.")
            }
        }

        if (audioMode == AudioCaptureMode.MIC_ONLY || audioMode == AudioCaptureMode.MIXED_INTERNAL_MIC) {
            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    micAudioRecord = record
                    Log.d(TAG, "Mic AudioRecord inicializado: 48000Hz, Estéreo, PCM 16-bit")
                } else {
                    Log.e(TAG, "Mic AudioRecord state != STATE_INITIALIZED (${record.state})")
                    record.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao instanciar Mic AudioRecord", e)
            }
        }
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
     * TAREFA 2: Pipeline de Alimentação Entrada/Saída do Codec de Áudio
     * Validação estrita do AudioRecord e injeção de amostras PCM válidas (bytesRead > 0).
     */
    private fun runAudioPipelineLoop() {
        val internalRecord = internalAudioRecord
        val micRecord = micAudioRecord

        val readBufferInternal = ByteArray(CHUNK_SIZE_BYTES)
        val readBufferMic = ByteArray(CHUNK_SIZE_BYTES)
        val mixedBuffer = ByteArray(CHUNK_SIZE_BYTES)

        while (isRecording.get() && !Thread.currentThread().isInterrupted) {
            if (isPaused.get()) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
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

                    // TAREFA 2: Verificação de Inicialização do AudioRecord
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

                    // TAREFA 2: Tratamento do Retorno de Leitura
                    val bytesRead = record.read(readBufferInternal, 0, readBufferInternal.size)
                    if (bytesRead <= 0) {
                        // Se retornar ERROR_INVALID_OPERATION (-3) ou ERROR_BAD_VALUE (-2), NÃO envie para o codec!
                        Log.e("DexRecorderAudio", "Falha na leitura do AudioRecord. Código: $bytesRead")
                        drainOutput(endOfStream = false)
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }

                    applyVolume(readBufferInternal, bytesRead, internalVolume)
                    feedCodecInput(readBufferInternal, bytesRead)
                    drainOutput(endOfStream = false)
                }

                AudioCaptureMode.MIC_ONLY -> {
                    val record = micRecord
                    if (record == null) {
                        drainOutput(endOfStream = false)
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }

                    // TAREFA 2: Verificação de Inicialização do AudioRecord
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

                    // TAREFA 2: Tratamento do Retorno de Leitura
                    val bytesRead = record.read(readBufferMic, 0, readBufferMic.size)
                    if (bytesRead <= 0) {
                        // Se retornar ERROR_INVALID_OPERATION (-3) ou ERROR_BAD_VALUE (-2), NÃO envie para o codec!
                        Log.e("DexRecorderAudio", "Falha na leitura do AudioRecord. Código: $bytesRead")
                        drainOutput(endOfStream = false)
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }

                    applyVolume(readBufferMic, bytesRead, micVolume)
                    feedCodecInput(readBufferMic, bytesRead)
                    drainOutput(endOfStream = false)
                }

                AudioCaptureMode.MIXED_INTERNAL_MIC -> {
                    val intRec = internalRecord
                    val mRec = micRecord

                    if (intRec != null && intRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try { intRec.startRecording() } catch (e: Exception) { Log.e("DexRecorderAudio", "Falha ao iniciar intRec: ${e.message}") }
                    }
                    if (mRec != null && mRec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        try { mRec.startRecording() } catch (e: Exception) { Log.e("DexRecorderAudio", "Falha ao iniciar mRec: ${e.message}") }
                    }

                    val intRead = intRec?.read(readBufferInternal, 0, readBufferInternal.size) ?: -1
                    val mRead = mRec?.read(readBufferMic, 0, readBufferMic.size) ?: -1

                    if (intRead <= 0 && mRead <= 0) {
                        Log.e("DexRecorderAudio", "Falha na leitura do AudioRecord. Código: int=$intRead, mic=$mRead")
                        drainOutput(endOfStream = false)
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                        continue
                    }

                    val finalBuffer: ByteArray
                    val finalBytes: Int

                    if (intRead > 0 && mRead > 0) {
                        val count = maxOf(intRead, mRead)
                        mixPcmStereo(
                            internalBuffer = readBufferInternal,
                            internalLength = intRead,
                            internalVol = internalVolume,
                            micBuffer = readBufferMic,
                            micLength = mRead,
                            micVol = micVolume,
                            output = mixedBuffer,
                            maxFrames = count / 2
                        )
                        finalBuffer = mixedBuffer
                        finalBytes = (count / BYTES_PER_FRAME) * BYTES_PER_FRAME
                    } else if (intRead > 0) {
                        applyVolume(readBufferInternal, intRead, internalVolume)
                        finalBuffer = readBufferInternal
                        finalBytes = intRead
                    } else {
                        applyVolume(readBufferMic, mRead, micVolume)
                        finalBuffer = readBufferMic
                        finalBytes = mRead
                    }

                    if (finalBytes > 0) {
                        feedCodecInput(finalBuffer, finalBytes)
                        drainOutput(endOfStream = false)
                    }
                }

                AudioCaptureMode.MUTE -> break
            }
        }

        Log.d(TAG, "Loop de áudio encerrado.")
    }

    /**
     * TAREFA 2: Alimentar Entrada (Input Buffer)
     * - audioCodec.dequeueInputBuffer(10000L)
     * - transfere os bytes para o inputBuffer
     * - audioCodec.queueInputBuffer(inputIndex, 0, bytesRead, audioPtsUs, 0)
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
     * TAREFA 2: Drenar Saída (Output Buffer)
     * - audioCodec.dequeueOutputBuffer(bufferInfo, 10000L)
     * - se INFO_OUTPUT_FORMAT_CHANGED: audioTrackIndex = mediaMuxer.addTrack(audioCodec.outputFormat)
     * - se >= 0: synchronized(muxerLock) { writeSampleData(...) } e releaseOutputBuffer
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
     * TAREFA 3: Validação de Trilha no Encerramento
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

        // TAREFA 3: Envio de BUFFER_FLAG_END_OF_STREAM para o codec
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

                // Drena todos os pacotes codificados pendentes para o MediaMuxer
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

    private fun mixPcmStereo(
        internalBuffer: ByteArray,
        internalLength: Int,
        internalVol: Float,
        micBuffer: ByteArray,
        micLength: Int,
        micVol: Float,
        output: ByteArray,
        maxFrames: Int
    ) {
        for (i in 0 until maxFrames) {
            val idx = i * 2

            val s1 = if (idx + 1 < internalLength) {
                val low = internalBuffer[idx].toInt() and 0xFF
                val high = internalBuffer[idx + 1].toInt()
                ((high shl 8) or low).toShort()
            } else 0

            val s2 = if (idx + 1 < micLength) {
                val low = micBuffer[idx].toInt() and 0xFF
                val high = micBuffer[idx + 1].toInt()
                ((high shl 8) or low).toShort()
            } else 0

            val mixed = ((s1 * internalVol) + (s2 * micVol)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            output[idx] = (mixed.toInt() and 0xFF).toByte()
            output[idx + 1] = ((mixed.toInt() shr 8) and 0xFF).toByte()
        }
    }
}
