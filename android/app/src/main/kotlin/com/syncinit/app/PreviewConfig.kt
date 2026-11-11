// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app

/** Shared cloud-media PCM format and session limits. */
internal object PreviewConfig {
    const val MAX_SPEAKERS = 8
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNELS = 2
    const val CHUNK_FRAMES = 960
    const val CHUNK_PERIOD_NS = 20_000_000L
}
