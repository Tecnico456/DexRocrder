package com.example.audio

import kotlin.math.max
import kotlin.math.min

/**
 * High-performance real-time 16-bit PCM software audio mixer.
 * Combines internal system playback stream and microphone stream with volume gains
 * and dynamic soft-clipping prevention to avoid digital clipping and distortion.
 */
class AudioMixer(
    var internalVolume: Float = 1.0f,
    var micVolume: Float = 1.0f
) {
    /**
     * Mixes two 16-bit PCM short buffers into a destination short buffer.
     * Handles cases where either buffer has fewer samples (pads with silence).
     */
    fun mixPcm(
        internalPcm: ShortArray,
        internalSamplesRead: Int,
        micPcm: ShortArray,
        micSamplesRead: Int,
        outputPcm: ShortArray
    ): Int {
        val maxSamples = max(internalSamplesRead, micSamplesRead)
        val limit = min(maxSamples, outputPcm.size)

        for (i in 0 until limit) {
            val sampleInt = if (i < internalSamplesRead) internalPcm[i].toFloat() * internalVolume else 0f
            val sampleMic = if (i < micSamplesRead) micPcm[i].toFloat() * micVolume else 0f

            // Sum both channels
            var mixed = sampleInt + sampleMic

            // Soft-knee limiting to prevent harsh digital distortion
            mixed = softLimit(mixed)

            outputPcm[i] = mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return limit
    }

    /**
     * Applies smooth saturation curve if amplitude exceeds threshold
     */
    private fun softLimit(sample: Float): Float {
        val threshold = 28000f // Headroom before 32767
        val absVal = kotlin.math.abs(sample)

        return if (absVal <= threshold) {
            sample
        } else {
            val sign = if (sample > 0) 1f else -1f
            val excess = absVal - threshold
            val compressed = threshold + (excess / (1f + (excess / 12000f)))
            sign * min(compressed, 32760f)
        }
    }
}
