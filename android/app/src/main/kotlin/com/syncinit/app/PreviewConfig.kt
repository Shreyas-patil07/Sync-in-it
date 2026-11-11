// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app

/** Cloud media format and bounded native PCM diagnostics shared by the Android client. */
internal object PreviewConfig {
    const val MAX_SPEAKERS = 8
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNELS = 2
    const val CHUNK_FRAMES = 960
    const val CHUNK_PERIOD_NS = 20_000_000L
    const val PCM_BUFFER_SECONDS = 20
    const val MAX_PCM_BUFFER_FRAMES = SAMPLE_RATE_HZ * PCM_BUFFER_SECONDS
}
