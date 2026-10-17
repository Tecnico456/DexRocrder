package com.example.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Thread-safe MediaMuxer coordinator enforcing strict Android synchronization rules:
 *
 * TAREFA 1: Trava de concorrência no MediaMuxer (Causa real do vídeo pulando).
 * O método mediaMuxer.writeSampleData() NÃO é thread-safe. Duas threads gravando
 * simultaneamente corrompem a tabela de índices (stts/ctts) do arquivo MP4.
 *
 * Utiliza um objeto de bloqueio explícito: private val muxerLock = Any()
 * Envolve TODAS as chamadas de escrita tanto no loop de vídeo quanto no de áudio dentro de:
 * synchronized(muxerLock) {
 *     if (isMuxerStarted) {
 *         mediaMuxer.writeSampleData(trackIndex, encodedData, bufferInfo)
 *     }
 * }
 * E garante que mediaMuxer.stop() e mediaMuxer.release() também sejam executados dentro de synchronized(muxerLock).
 */
class MediaMuxerManager(
    outputPath: String? = null,
    outputFd: FileDescriptor? = null,
    private val expectedTrackCount: Int = 2
) {
    companion object {
        private const val TAG = "DexMuxerManager"
    }

    private val mediaMuxer: MediaMuxer = if (outputFd != null) {
        MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } else {
        MediaMuxer(outputPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    // Objeto de bloqueio explícito mandatário para proteção concorrente
    val muxerLock = Any()
    val lock: Any get() = muxerLock
    val rawMuxer: MediaMuxer get() = mediaMuxer

    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1

    @Volatile
    var isVideoTrackAdded: Boolean = false
        private set

    @Volatile
    var isAudioTrackAdded: Boolean = false
        private set

    @Volatile
    var isMuxerStarted: Boolean = false
        private set

    /**
     * Registra o formato de vídeo assim que o MediaCodec emite INFO_OUTPUT_FORMAT_CHANGED.
     */
    fun addVideoTrack(format: MediaFormat): Int = synchronized(muxerLock) {
        if (isMuxerStarted) {
            Log.w(TAG, "Tentativa de adicionar faixa de vídeo após MediaMuxer já ter iniciado")
            return videoTrackIndex
        }
        if (!isVideoTrackAdded) {
            videoTrackIndex = mediaMuxer.addTrack(format)
            isVideoTrackAdded = true
            Log.d(TAG, "Faixa de Vídeo adicionada ao Muxer: index=$videoTrackIndex, format=$format")
            checkStartCondition()
        }
        return videoTrackIndex
    }

    /**
     * Registra o formato de áudio assim que o MediaCodec emite INFO_OUTPUT_FORMAT_CHANGED.
     */
    fun addAudioTrack(format: MediaFormat): Int = synchronized(muxerLock) {
        if (isMuxerStarted) {
            Log.w(TAG, "Tentativa de adicionar faixa de áudio após MediaMuxer já ter iniciado")
            return audioTrackIndex
        }
        if (!isAudioTrackAdded) {
            audioTrackIndex = mediaMuxer.addTrack(format)
            isAudioTrackAdded = true
            Log.d(TAG, "Faixa de Áudio adicionada ao Muxer: index=$audioTrackIndex, format=$format")
            checkStartCondition()
        }
        return audioTrackIndex
    }

    /**
     * mediaMuxer.start() SÓ É CHAMADO quando os codecs esperados tiverem registrado suas faixas.
     */
    private fun checkStartCondition() {
        val ready = if (expectedTrackCount == 1) {
            isVideoTrackAdded
        } else {
            isVideoTrackAdded && isAudioTrackAdded
        }

        if (ready && !isMuxerStarted) {
            try {
                mediaMuxer.start()
                isMuxerStarted = true
                Log.d(TAG, "MediaMuxer INICIADO com sucesso! (Vídeo=$isVideoTrackAdded, Áudio=$isAudioTrackAdded)")
            } catch (e: Exception) {
                Log.e(TAG, "Falha crítica ao iniciar MediaMuxer", e)
            }
        }
    }

    /**
     * TAREFA 1: Envolve TODAS as chamadas de escrita tanto no loop de vídeo quanto no de áudio
     * dentro do bloco sincronizado:
     * synchronized(muxerLock) {
     *     if (isMuxerStarted) {
     *         mediaMuxer.writeSampleData(trackIndex, encodedData, bufferInfo)
     *     }
     * }
     */
    fun writeSampleData(trackIndex: Int, encodedData: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (isMuxerStarted) {
                if (bufferInfo.size <= 0 || (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    return
                }
                try {
                    mediaMuxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao gravar amostra na faixa $trackIndex (PTS=${bufferInfo.presentationTimeUs} us)", e)
                }
            }
        }
    }

    /**
     * Garante que o MediaMuxer.stop() e MediaMuxer.release() também sejam executados
     * dentro de synchronized(muxerLock).
     */
    fun stopAndRelease() = synchronized(muxerLock) {
        if (isMuxerStarted) {
            try {
                mediaMuxer.stop()
                Log.d(TAG, "MediaMuxer parado com sucesso")
            } catch (e: Exception) {
                Log.w(TAG, "Exceção ao parar MediaMuxer", e)
            } finally {
                isMuxerStarted = false
            }
        }
        try {
            mediaMuxer.release()
            Log.d(TAG, "MediaMuxer liberado com sucesso")
        } catch (e: Exception) {
            Log.w(TAG, "Exceção ao liberar MediaMuxer", e)
        }
    }
}
