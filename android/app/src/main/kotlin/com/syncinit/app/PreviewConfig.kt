// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app

/**
 * Single source of truth for Android preview networking, buffering and test-stream limits.
 * The published design target (6–10 total phones) is not a measured capacity guarantee.
 */
internal object PreviewConfig {
    const val CONTROL_PORT = 47_800
    const val DEFAULT_AUDIO_PORT = 47_801
    const val AUDIO_LISTENER_REQUESTED_PORT = 0
    const val TIME_PORT = 47_802
    const val RTP_AUDIO_PORT = 47_803
    const val RTP_AUDIO_REGISTRATION_TIMEOUT_MS = 1_000
    const val RTP_AUDIO_REGISTRATION_ATTEMPTS = 5
    val RTP_AUDIO_ENABLED: Boolean = BuildConfig.RTP_AUDIO_ENABLED

    const val MAX_SPEAKERS = 8
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNELS = 2
    const val CHUNK_FRAMES = 960
    const val CHUNK_PERIOD_NS = 20_000_000L
    const val PCM_BUFFER_SECONDS = 20
    const val MAX_PCM_BUFFER_FRAMES = SAMPLE_RATE_HZ * PCM_BUFFER_SECONDS
    const val AUDIO_QUEUE_CHUNKS = 25
    const val AUDIO_QUEUE_LIMIT_MS = AUDIO_QUEUE_CHUNKS * CHUNK_PERIOD_NS / 1_000_000L

    const val MIN_READY_BUFFER_MS = 3_000L
    const val MAX_CLOCK_RESIDUAL_US = 250L
    const val READY_RETRY_INTERVAL_NS = 1_000_000_000L
    const val GROUP_START_LEAD_NS = 1_000_000_000L
    const val GROUP_ADMISSION_WINDOW_NS = 15_000_000_000L
    const val RELEASE_GROUP_READINESS_TIMEOUT_NS = 45_000_000_000L
    const val DEBUG_GROUP_READINESS_TIMEOUT_NS = 90_000_000_000L
    val GROUP_READINESS_TIMEOUT_NS: Long
        get() = if (BuildConfig.DEBUG) {
            DEBUG_GROUP_READINESS_TIMEOUT_NS
        } else {
            RELEASE_GROUP_READINESS_TIMEOUT_NS
        }
    const val GROUP_START_BUFFER_MS = 2_000L
    const val STARTUP_LEAD_MS = 1_000

    const val AUDIO_READ_TIMEOUT_MS = 0
    const val AUDIO_ACCEPT_TIMEOUT_MS = 15_000
    const val TCP_CONNECT_TIMEOUT_MS = 5_000
    const val TCP_HANDSHAKE_READ_TIMEOUT_MS = 10_000
    const val TIME_SYNC_RECEIVE_TIMEOUT_MS = 500
    const val JOIN_BURST_PROBES = 20
    const val JOIN_BURST_INTERVAL_MS = 50L
    const val UNSTABLE_RETRY_INTERVAL_MS = 500L
    const val STEADY_STATE_INTERVAL_MS = 2_000L

    const val ACOUSTIC_MARKER_DURATION_MS = 20
    const val ACOUSTIC_MARKER_FRAMES = 960
    const val ACOUSTIC_MARKER_BASE_HZ = 1_500
    const val ACOUSTIC_MARKER_STEP_HZ = 750
    const val ACOUSTIC_MARKER_AMPLITUDE = 2_500
}
