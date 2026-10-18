package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.model.AudioCaptureMode
import kotlinx.coroutines.CoroutineScope
import java.util.Arrays
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

/**
 * High-stability audio recorder for Android 10, 14, and 15 (Samsung S23 Ultra / One UI 7.0).
 *
 * Implements:
 * 1. android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) on capture threads.
 * 2. Asynchronous decoupling using ConcurrentLinkedQueue<ByteArray> so that AudioRecord.read()
 *    is never stalled by encoding routines or sleep delays.
 * 3. Exact buffer chunk size of 2048 bytes (1024 16-bit PCM samples) for clean hardware alignment.
 * 4. 4x getMinBufferSize for AudioRecord internal hardware buffers to absorb OS jitter.
 */
class MixedAudioRecorder(
    private val mediaProjection: MediaProjection?,
    private val audioMode: AudioCaptureMode,
    private val audioEncoder: AudioEncoder,
    private val sampleRate: Int = 48000,
    var internalVolume: Float = 1.0f,
    var micVolume: Float = 1.0f
) {
    companion object {
        private const val TAG = "DexRecorderAudio"
        // 1024 shorts mono = 2048 bytes exatos
        private const val CHUNK_SAMPLES = 1024
        private const val CHUNK_BYTES = CHUNK_SAMPLES * 2 // 2048 bytes
    }

    private var internalAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    // Mono 48000Hz 16-bit PCM (Galaxy / Android 15 Snapdragon native rate)
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val workerThreads = CopyOnWriteArrayList<Thread>()

    @SuppressLint("MissingPermission")
    fun initInternalAudioRecord(
        projection: MediaProjection,
        sampleRate: Int,
        bufferSize: Int
    ): AudioRecord? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "AudioPlaybackCapture requer Android 10+ (API 29+)")
            return null
        }

        return try {
            val audioPlaybackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(audioPlaybackConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Internal AudioRecord inicializado: SampleRate=$sampleRate, MONO, Buffer=$bufferSize")
                record
            } else {
                Log.e(TAG, "Falha crítica: Internal AudioRecord state != STATE_INITIALIZED (state=${record.state})")
                record.release()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao instanciar Internal AudioRecord com AudioPlaybackCapture", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun initMicAudioRecord(sampleRate: Int, bufferSize: Int): AudioRecord? {
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Mic AudioRecord inicializado: SampleRate=$sampleRate, MONO, Buffer=$bufferSize")
                record
            } else {
                Log.e(TAG, "Falha crítica: Mic AudioRecord state != STATE_INITIALIZED (state=${record.state})")
                record.release()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao instanciar Mic AudioRecord", e)
            null
        }
    }

    fun start(scope: CoroutineScope) {
        if (audioMode == AudioCaptureMode.MUTE) {
            Log.d(TAG, "Modo de áudio MUTE. Nenhuma captura será iniciada.")
            return
        }

        // Buffer ampliado (4x minBufferSize) para prevenir engasgos/buffer overrun
        val calculatedMin = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = if (calculatedMin > 0) (calculatedMin * 4).coerceAtLeast(16384) else 16384

        isRecording.set(true)
        isPaused.set(false)
        workerThreads.clear()

        when (audioMode) {
            AudioCaptureMode.INTERNAL_ONLY -> {
                Log.d(TAG, "Iniciando modo: Apenas Áudio Interno")
                if (mediaProjection == null) {
                    Log.e(TAG, "MediaProjection é nulo! Não é possível capturar áudio interno.")
                    return
                }

                internalAudioRecord = initInternalAudioRecord(mediaProjection, sampleRate, bufferSize)
                if (internalAudioRecord == null || internalAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Internal AudioRecord inválido. Abortando gravação de áudio.")
                    return
                }

                try {
                    internalAudioRecord?.startRecording()
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao chamar startRecording() no internalAudioRecord", e)
                    return
                }

                startInternalOnlyPipeline(internalAudioRecord!!, sampleRate)
            }

            AudioCaptureMode.MIC_ONLY -> {
                Log.d(TAG, "Iniciando modo: Apenas Microfone")
                micAudioRecord = initMicAudioRecord(sampleRate, bufferSize)
                if (micAudioRecord == null || micAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Mic AudioRecord inválido. Abortando gravação de áudio.")
                    return
                }

                try {
                    micAudioRecord?.startRecording()
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao chamar startRecording() no micAudioRecord", e)
                    return
                }

                startMicOnlyPipeline(micAudioRecord!!, sampleRate)
            }

            AudioCaptureMode.MIXED_INTERNAL_MIC -> {
                Log.d(TAG, "Iniciando modo: Áudio Interno + Microfone com Mixer PCM")
                if (mediaProjection != null) {
                    internalAudioRecord = initInternalAudioRecord(mediaProjection, sampleRate, bufferSize)
                }
                micAudioRecord = initMicAudioRecord(sampleRate, bufferSize)

                val internalOk = internalAudioRecord?.state == AudioRecord.STATE_INITIALIZED
                val micOk = micAudioRecord?.state == AudioRecord.STATE_INITIALIZED

                if (!internalOk && !micOk) {
                    Log.e(TAG, "Ambos AudioRecords falharam na inicialização.")
                    return
                } else if (!internalOk) {
                    Log.w(TAG, "Áudio interno não inicializou. Usando apenas Microfone.")
                    try { micAudioRecord?.startRecording() } catch (_: Exception) {}
                    startMicOnlyPipeline(micAudioRecord!!, sampleRate)
                } else if (!micOk) {
                    Log.w(TAG, "Microfone não inicializou. Usando apenas Áudio Interno.")
                    try { internalAudioRecord?.startRecording() } catch (_: Exception) {}
                    startInternalOnlyPipeline(internalAudioRecord!!, sampleRate)
                } else {
                    try {
                        internalAudioRecord?.startRecording()
                        micAudioRecord?.startRecording()
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao iniciar AudioRecords", e)
                    }
                    startMixedPipeline(internalAudioRecord!!, micAudioRecord!!, sampleRate)
                }
            }

            AudioCaptureMode.MUTE -> { /* no-op */ }
        }
    }

    /**
     * Pipeline desacoplado para Áudio Interno:
     * - Thread 1 (URGENT_AUDIO): Apenas lê do hardware com AudioRecord.read() e alimenta a ConcurrentLinkedQueue.
     * - Thread 2 (Dispatcher): Retira da fila e envia ao AudioEncoder (ou envia silêncio se sem dados).
     */
    private fun startInternalOnlyPipeline(record: AudioRecord, sampleRate: Int) {
        val rawQueue = ConcurrentLinkedQueue<ByteArray>()
        val chunkDurationMs = ((CHUNK_SAMPLES.toLong() * 1000L) / sampleRate).coerceAtLeast(10L)

        // 1. Thread de Leitura do Hardware com prioridade URGENT_AUDIO
        val readerThread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                Log.d(TAG, "Thread Leitora de Áudio Interno iniciada com THREAD_PRIORITY_URGENT_AUDIO")
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível definir THREAD_PRIORITY_URGENT_AUDIO", e)
            }

            val readBuffer = ShortArray(CHUNK_SAMPLES)

            while (isRecording.get() && !Thread.currentThread().isInterrupted) {
                if (isPaused.get()) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }

                // Leitura bloqueante com alinhamento exato de 2048 bytes (1024 shorts)
                val shortsRead = record.read(readBuffer, 0, CHUNK_SAMPLES, AudioRecord.READ_BLOCKING)

                if (shortsRead > 0) {
                    val byteBuffer = ByteArray(shortsRead * 2)
                    for (i in 0 until shortsRead) {
                        val sample = (readBuffer[i].toFloat() * internalVolume)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                        val idx = i * 2
                        byteBuffer[idx] = (sample.toInt() and 0xFF).toByte()
                        byteBuffer[idx + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                    }
                    rawQueue.offer(byteBuffer)
                }
            }
            Log.d(TAG, "Thread Leitora de Áudio Interno finalizada.")
        }, "DexAudio-InternalReader").apply {
            start()
        }
        workerThreads.add(readerThread)

        // 2. Thread Despachante: Envia os buffers prontos ao AudioEncoder
        val dispatcherThread = Thread({
            Log.d(TAG, "Thread Despachante de Áudio Interno iniciada.")
            val silenceBytes = ByteArray(CHUNK_BYTES)

            // Pacote inicial para inicialização do Muxer
            audioEncoder.encodePcm(silenceBytes, silenceBytes.size)

            while (isRecording.get() && !Thread.currentThread().isInterrupted) {
                if (isPaused.get()) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }

                val chunk = rawQueue.poll()
                if (chunk != null) {
                    audioEncoder.encodePcm(chunk, chunk.size)
                } else {
                    // Sem dados no momento: injeta silêncio cronometrado mantendo a sincronia de tempo
                    audioEncoder.encodePcm(silenceBytes, silenceBytes.size)
                    try { Thread.sleep(chunkDurationMs) } catch (_: InterruptedException) { break }
                }
            }
            Log.d(TAG, "Thread Despachante de Áudio Interno finalizada.")
        }, "DexAudio-InternalDispatcher").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        workerThreads.add(dispatcherThread)
    }

    /**
     * Pipeline desacoplado para Microfone.
     */
    private fun startMicOnlyPipeline(record: AudioRecord, sampleRate: Int) {
        val rawQueue = ConcurrentLinkedQueue<ByteArray>()
        val chunkDurationMs = ((CHUNK_SAMPLES.toLong() * 1000L) / sampleRate).coerceAtLeast(10L)

        val readerThread = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                Log.d(TAG, "Thread Leitora de Microfone iniciada com THREAD_PRIORITY_URGENT_AUDIO")
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível definir THREAD_PRIORITY_URGENT_AUDIO", e)
            }

            val readBuffer = ShortArray(CHUNK_SAMPLES)

            while (isRecording.get() && !Thread.currentThread().isInterrupted) {
                if (isPaused.get()) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }

                val shortsRead = record.read(readBuffer, 0, CHUNK_SAMPLES, AudioRecord.READ_BLOCKING)

                if (shortsRead > 0) {
                    val byteBuffer = ByteArray(shortsRead * 2)
                    for (i in 0 until shortsRead) {
                        val sample = (readBuffer[i].toFloat() * micVolume)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                        val idx = i * 2
                        byteBuffer[idx] = (sample.toInt() and 0xFF).toByte()
                        byteBuffer[idx + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                    }
                    rawQueue.offer(byteBuffer)
                }
            }
            Log.d(TAG, "Thread Leitora de Microfone finalizada.")
        }, "DexAudio-MicReader").apply {
            start()
        }
        workerThreads.add(readerThread)

        val dispatcherThread = Thread({
            Log.d(TAG, "Thread Despachante de Microfone iniciada.")
            val silenceBytes = ByteArray(CHUNK_BYTES)
            audioEncoder.encodePcm(silenceBytes, silenceBytes.size)

            while (isRecording.get() && !Thread.currentThread().isInterrupted) {
                if (isPaused.get()) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }

                val chunk = rawQueue.poll()
                if (chunk != null) {
                    audioEncoder.encodePcm(chunk, chunk.size)
                } else {
                    audioEncoder.encodePcm(silenceBytes, silenceBytes.size)
                    try { Thread.sleep(chunkDurationMs) } catch (_: InterruptedException) { break }
                }
            }
            Log.d(TAG, "Thread Despachante de Microfone finalizada.")
        }, "DexAudio-MicDispatcher").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        workerThreads.add(dispatcherThread)
    }

    /**
     * Pipeline desacoplado para Mixagem (Áudio Interno + Microfone):
     * - Cada produtor roda com THREAD_PRIORITY_URGENT_AUDIO e envia para sua respectiva ConcurrentLinkedQueue.
     * - O Mixer retira e combina as amostras aplicando soft-limiting.
     */
    private fun startMixedPipeline(
        internalRec: AudioRecord,
        micRec: AudioRecord,
        sampleRate: Int
    ) {
        val internalQueue = ConcurrentLinkedQueue<ShortArray>()
        val micQueue = ConcurrentLinkedQueue<ShortArray>()
        val chunkDurationMs = ((CHUNK_SAMPLES.toLong() * 1000L) / sampleRate).coerceAtLeast(10L)

        // Produtor Áudio Interno
        val internalProducer = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}

            val readBuffer = ShortArray(CHUNK_SAMPLES)
            while (isRecording.get() && !Thread.currentThread().isInterrupted) {
                if (isPaused.get()) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }
                val read = internalRec.read(readBuffer, 0, CHUNK_SAMPLES, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    val chunk = ShortArray(read)
                    System.arraycopy(readBuffer, 0, chunk, 0, read)
                    internalQueue.offer(chunk)
                }
            }
        }, "DexAudio-InternalMixedReader").apply {
            start()
        }
        workerThreads.add(internalProducer)

        // Produtor Microfone
        val micProducer = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}

            val readBuffer = ShortArray(CHUNK_SAMPLES)
            while (isRecording.get() && !Thread.currentThread().isInterrupted) {
                if (isPaused.get()) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }
                val read = micRec.read(readBuffer, 0, CHUNK_SAMPLES, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    val chunk = ShortArray(read)
                    System.arraycopy(readBuffer, 0, chunk, 0, read)
                    micQueue.offer(chunk)
                }
            }
        }, "DexAudio-MicMixedReader").apply {
            start()
        }
        workerThreads.add(micProducer)

        // Mixer PCM Consumidor
        val mixerConsumer = Thread({
            Log.d(TAG, "Mixer PCM iniciado com filas desacopladas.")
            val silenceShorts = ShortArray(CHUNK_SAMPLES)
            val outputBytes = ByteArray(CHUNK_BYTES)

            audioEncoder.encodePcm(outputBytes, outputBytes.size)

            while (isRecording.get() && !Thread.currentThread().isInterrupted) {
                if (isPaused.get()) {
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }

                val intChunk = internalQueue.poll()
                val micChunk = micQueue.poll()

                val safeInt = intChunk ?: silenceShorts
                val safeMic = micChunk ?: silenceShorts

                for (i in 0 until CHUNK_SAMPLES) {
                    val sampleInt = (if (i < safeInt.size) safeInt[i].toFloat() else 0f) * internalVolume
                    val sampleMic = (if (i < safeMic.size) safeMic[i].toFloat() else 0f) * micVolume

                    val sum = sampleInt + sampleMic
                    val limited = softLimit(sum)
                    val clamped = limited.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

                    val byteIdx = i * 2
                    outputBytes[byteIdx] = (clamped.toInt() and 0xFF).toByte()
                    outputBytes[byteIdx + 1] = ((clamped.toInt() shr 8) and 0xFF).toByte()
                }

                audioEncoder.encodePcm(outputBytes, outputBytes.size)

                if (intChunk == null && micChunk == null) {
                    try { Thread.sleep(chunkDurationMs) } catch (_: InterruptedException) { break }
                }
            }
            Log.d(TAG, "Mixer PCM finalizado.")
        }, "DexAudio-PcmMixer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        workerThreads.add(mixerConsumer)
    }

    private fun softLimit(sample: Float): Float {
        val threshold = 28000f
        val absVal = abs(sample)
        return if (absVal <= threshold) {
            sample
        } else {
            val sign = if (sample > 0) 1f else -1f
            val excess = absVal - threshold
            val compressed = threshold + (excess / (1f + (excess / 12000f)))
            sign * min(compressed, 32760f)
        }
    }

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
    }

    fun stop() {
        isRecording.set(false)

        for (thread in workerThreads) {
            try {
                thread.interrupt()
            } catch (_: Exception) {}
        }
        workerThreads.clear()

        try {
            internalAudioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    try { stop() } catch (_: Exception) {}
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao liberar internalAudioRecord", e)
        } finally {
            internalAudioRecord = null
        }

        try {
            micAudioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    try { stop() } catch (_: Exception) {}
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao liberar micAudioRecord", e)
        } finally {
            micAudioRecord = null
        }
    }
}
