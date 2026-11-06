// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.audio

import com.syncinit.app.PreviewConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Receiver-specific acoustic signature overlaid only on the generated diagnostic test stream. */
internal object AcousticTestMarker {
    fun overlayInPlace(
        samples: ShortArray,
        firstSampleIndex: Long,
        sampleRateHz: Int,
        channels: Int,
        receiverId: Int
    ) {
        require(sampleRateHz == PreviewConfig.SAMPLE_RATE_HZ)
        require(channels in 1..PreviewConfig.CHANNELS)
        require(receiverId in 1..PreviewConfig.MAX_SPEAKERS)
        require(samples.size % channels == 0)

        val frequencyHz = PreviewConfig.ACOUSTIC_MARKER_BASE_HZ +
            (receiverId - 1) * PreviewConfig.ACOUSTIC_MARKER_STEP_HZ
        val frameCount = samples.size / channels
        val denominator = (PreviewConfig.ACOUSTIC_MARKER_FRAMES - 1).toDouble()

        for (frame in 0 until frameCount) {
            val absoluteSample = firstSampleIndex + frame
            val markerOffset = (absoluteSample % sampleRateHz.toLong()).toInt()
            if (markerOffset !in 0 until PreviewConfig.ACOUSTIC_MARKER_FRAMES) continue

            val envelope = 0.5 - 0.5 * cos(2.0 * PI * markerOffset / denominator)
            val phase = 2.0 * PI * frequencyHz * markerOffset / sampleRateHz
            val marker = (PreviewConfig.ACOUSTIC_MARKER_AMPLITUDE * envelope * sin(phase)).toInt()

            for (channel in 0 until channels) {
                val index = frame * channels + channel
                val mixed = samples[index].toInt() + marker
                samples[index] = mixed
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
    }
}
