package com.example.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.example.video.MediaMuxerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * Encodes raw PCM audio stream to AAC (audio/mp4a-latm) using MediaCodec.
 *
 * Implements strict thread decoupling:
 * 1. An incoming PCM queue receives data from AudioRecord threads.
 * 2. An independent Coroutine/Worker on Dispatchers.IO feeds the MediaCodec input buffers
 *    and drains output buffers to MediaMuxer without ever blocking AudioRecord.read() or Video processing.
 */
class AudioEncoder(
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 1,
    private val bitRate: Int = 128000,
    private val muxerManager: MediaMuxerManager
) {
    companion object {
        private const val TAG = "DexAudioEncoder"
    }

    private val mimeType = "audio/mp4a-latm"
    private var mediaCodec: MediaCodec? = null
    private var audioTrackIndex: Int = -1
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile
    private var isRunning: Boolean = false

    private val pcmQueue = LinkedBlockingQueue<ByteArray>(32)
    private var workerJob: Job? = null

    // TAREFA 2: Variáveis contínuas para cálculo estritamente linear do PTS de áudio
    private var totalAudioBytesRead = 0L
    private var totalAudioBytesWritten = 0L

    fun prepare() {
        val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        }

        val codec = MediaCodec.createEncoderByType(mimeType)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        mediaCodec = codec
        isRunning = true
        pcmQueue.clear()

        Log.d(TAG, "AudioEncoder preparado: sampleRate=$sampleRate, channels=$channelCount, bitrate=$bitRate")
    }

    /**
     * Starts the independent audio encoding and draining loop in Dispatchers.IO.
     */
    fun start(scope: CoroutineScope) {
        workerJob = scope.launch(Dispatchers.IO) {
            encodingLoop()
        }
    }

    /**
     * Called by AudioRecord threads to queue PCM audio without blocking on MediaCodec dequeue.
     */
    fun encodePcm(pcmBytes: ByteArray, length: Int) {
        if (!isRunning || length <= 0) return
        val copy = ByteArray(length)
        System.arraycopy(pcmBytes, 0, copy, 0, length)
        if (!pcmQueue.offer(copy)) {
            // Drops the oldest frame if the queue is full to preserve real-time synchronization
            pcmQueue.poll()
            pcmQueue.offer(copy)
        }
    }

    private fun encodingLoop() {
        val codec = mediaCodec ?: return
        Log.d(TAG, "Loop de processamento de Áudio iniciado em thread dedicada: ${Thread.currentThread().name}")

        while (isRunning && !Thread.currentThread().isInterrupted) {
            // 1. Process pending PCM input chunk
            val chunk = pcmQueue.poll()
            if (chunk != null) {
                try {
                    val inputBufferIndex = codec.dequeueInputBuffer(5_000L)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(chunk, 0, chunk.size)

                        // TAREFA 2: Cálculo contínuo do PTS de áudio estritamente pela quantidade acumulada de amostras processadas
                        val audioPtsUs = (totalAudioBytesRead * 1_000_000L) / (sampleRate.toLong() * channelCount.toLong() * 2L)
                        totalAudioBytesRead += chunk.size

                        codec.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            chunk.size,
                            audioPtsUs,
                            0
                        )
                    } else {
                        // Se o codec estiver ocupado, reinserimos o chunk para não perder áudio
                        pcmQueue.offer(chunk)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao enfileirar buffer de entrada do codec de áudio", e)
                }
            }

            // 2. Drain output buffers from Audio MediaCodec
            drainCodec(codec, endOfStream = false)

            if (chunk == null) {
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        Log.d(TAG, "Loop de processamento de Áudio finalizado.")
    }

    private fun drainCodec(codec: MediaCodec, endOfStream: Boolean) {
        while (true) {
            val outputBufferIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 5_000L)
            } catch (e: Exception) {
                Log.w(TAG, "Exceção em dequeueOutputBuffer de áudio", e)
                break
            }

            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    Log.d(TAG, "Formato do áudio alterado (INFO_OUTPUT_FORMAT_CHANGED): $newFormat")
                    audioTrackIndex = muxerManager.addAudioTrack(newFormat)
                }
                outputBufferIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            // TAREFA 2: Cálculo contínuo do PTS de áudio estritamente pela quantidade acumulada de amostras processadas
                            val audioPtsUs = (totalAudioBytesWritten * 1_000_000L) / (sampleRate.toLong() * channelCount.toLong() * 2L)
                            bufferInfo.presentationTimeUs = audioPtsUs
                            totalAudioBytesWritten += (1024L * channelCount.toLong() * 2L)

                            // Escreve os frames de áudio no MediaMuxer sincronizado
                            muxerManager.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "Áudio EOS alcançado")
                        break
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            workerJob?.cancel()
        } catch (_: Exception) {}

        try {
            val codec = mediaCodec
            if (codec != null) {
                val inputBufferIndex = codec.dequeueInputBuffer(10_000L)
                if (inputBufferIndex >= 0) {
                    val finalPtsUs = (totalAudioBytesRead * 1_000_000L) / (sampleRate.toLong() * channelCount.toLong() * 2L)
                    codec.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        0,
                        finalPtsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
                drainCodec(codec, endOfStream = true)
                codec.stop()
                codec.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exceção ao parar AudioEncoder", e)
        } finally {
            mediaCodec = null
            pcmQueue.clear()
        }
    }
}
