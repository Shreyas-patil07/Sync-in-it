// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.network

import com.syncinit.app.PreviewConfig

import android.content.Context
import android.net.Network
import android.os.SystemClock
import com.syncinit.app.audio.PcmAudioReceiver
import com.syncinit.app.core.NativeProtocol
import com.syncinit.app.core.NativeTimeSyncClient
import com.syncinit.transport.TcpFrameConnection
import com.syncinit.transport.FrameProtocolException
import com.syncinit.transport.UdpTimeSyncPacketMetadata
import java.io.Closeable
import java.net.InetAddress
import java.net.PortUnreachableException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/** Android receiver-side client for the control and separate audio admission channels. */
class ReceiverSessionClient(
    private val context: Context,
    private val onStatus: (String) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "syncinit-receiver-handshake").apply { isDaemon = true }
    }

    @Volatile private var controlConnection: TcpFrameConnection? = null
    @Volatile private var audioConnection: TcpFrameConnection? = null
    @Volatile private var audioPipeline: PcmAudioReceiver? = null

    fun connect(host: String, tokenHex: String, controlPort: Int = PreviewConfig.CONTROL_PORT) {
        check(!closed.get()) { "receiver client is closed" }
        check(started.compareAndSet(false, true)) { "receiver client has already started" }
        require(host.isNotBlank()) { "master address must not be blank" }
        require(controlPort in 1..65_535) { "control port is invalid" }
        val token = tokenHex.decodeSessionToken()
        val attemptId = UUID.randomUUID().toString().take(8)

        executor.execute {
            try {
                onStatus("[DIAG $attemptId] JOIN_BEGIN target=$host:$controlPort")
                onStatus(NetworkDiagnostics.networkSnapshot(context, host))
                val peerRoute = PeerNetworkSelector.select(context, host)
                onStatus(
                    "[DIAG $attemptId] PEER_NETWORK_SELECTED network=${peerRoute.network} " +
                        "interface=${peerRoute.interfaceName} route=${peerRoute.matchedRoute} " +
                        "internet=${peerRoute.hasInternet} validated=${peerRoute.isValidated}"
                )
                onStatus("[DIAG $attemptId] STAGE_CONTROL_TCP_CONNECT_BEGIN target=$host:$controlPort timeoutMs=${PreviewConfig.TCP_CONNECT_TIMEOUT_MS}")
                val control = connectTcp(
                    attemptId, "CONTROL", host, controlPort, PreviewConfig.TCP_HANDSHAKE_READ_TIMEOUT_MS, peerRoute.network
                )
                controlConnection = control
                diagnosticStage(attemptId, "CONTROL_HELLO_WRITE") {
                    control.writeEncodedFrame(NativeProtocol.encodeHelloFrame(token))
                }

                onStatus("[DIAG $attemptId] STAGE_WELCOME_READ_BEGIN timeoutMs=${PreviewConfig.TCP_HANDSHAKE_READ_TIMEOUT_MS}")
                val welcomeFrame = diagnosticStage(attemptId, "WELCOME_READ") {
                    control.readFrame() ?: error("master closed before WELCOME")
                }
                val welcome = diagnosticStage(attemptId, "WELCOME_DECODE") {
                    NativeProtocol.decodeWelcomeFrame(welcomeFrame)
                }
                onStatus(
                    "[DIAG $attemptId] WELCOME_ACCEPTED sessionId=${welcome.sessionId} " +
                        "receiverId=${welcome.receiverId} audioPort=${welcome.audioPort} timePort=${welcome.timePort}"
                )

                // The 10-second timeout protects only the WELCOME handshake. The control
                // channel is otherwise intentionally idle until the master sends an ANCHOR.
                diagnosticStage(attemptId, "CONTROL_IDLE_TIMEOUT_DISABLE") {
                    control.setReadTimeoutMs(0)
                }

                onStatus("Master accepted HELLO. Opening audio channel…")
                val audio = connectTcp(attemptId, "AUDIO", host, welcome.audioPort, 0, peerRoute.network)
                audioConnection = audio
                diagnosticStage(attemptId, "AUDIO_HELLO_WRITE") {
                    audio.writeEncodedFrame(NativeProtocol.encodeAudioHelloFrame(welcome))
                }

                val pipeline = diagnosticStage(attemptId, "AUDIO_PIPELINE_CREATE") {
                    PcmAudioReceiver(
                        audioConnection = audio,
                        controlConnection = control,
                        welcome = welcome,
                        onStatus = onStatus,
                        onFailure = { error ->
                            if (!closed.get()) {
                                onStatus(
                                    "[DIAG $attemptId] AUDIO_PIPELINE_FAILURE " +
                                        "audioLocal=${audio.localEndpoint} audioRemote=${audio.remoteEndpoint} " +
                                        NetworkDiagnostics.exceptionDetails(error)
                                )
                                onStatus("Audio receiver failed: ${error.message ?: error.javaClass.simpleName}")
                                closeWithReason("audio-pipeline-failure", attemptId)
                            }
                        }
                    )
                }
                audioPipeline = pipeline
                diagnosticStage(attemptId, "AUDIO_PIPELINE_START") { pipeline.start() }
                startControlReader(attemptId, control, pipeline)

                onStatus("[DIAG $attemptId] JOIN_TRANSPORT_READY control=${control.localEndpoint}->${control.remoteEndpoint} audio=${audio.localEndpoint}->${audio.remoteEndpoint}")
                onStatus("Control and audio channels established. Receiving PCM16 while acquiring the master clock…")
                runClockSync(
                    attemptId,
                    control,
                    host,
                    welcome.sessionId,
                    welcome.receiverId,
                    welcome.timePort,
                    pipeline,
                    peerRoute.network
                )
            } catch (error: Exception) {
                if (!closed.get()) {
                    onStatus("[DIAG $attemptId] JOIN_FAILED ${NetworkDiagnostics.exceptionDetails(error)}")
                    onStatus("[DIAG $attemptId] NETWORK_AT_FAILURE\n${NetworkDiagnostics.networkSnapshot(context, host)}")
                    onStatus("Join failed: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                closeWithReason("join-worker-exit", attemptId)
            }
        }
    }

    /**
     * Owns the receiver-side control read stream after WELCOME. Time-sync continues to write
     * READY on this connection; this reader handles the master's playback ANCHOR frames.
     */
    private fun startControlReader(
        attemptId: String,
        control: TcpFrameConnection,
        audioPipeline: PcmAudioReceiver
    ) {
        Thread({
            try {
                while (!closed.get()) {
                    val frame = control.readFrame()
                        ?: throw IOException("master closed the control channel")
                    if (frame.flags != 0) {
                        throw FrameProtocolException("control frame flags must be zero")
                    }
                    when (frame.messageType) {
                        NativeProtocol.ANCHOR_TYPE -> {
                            val anchor = NativeProtocol.decodeAnchorFrame(frame)
                            onStatus(
                                "[DIAG $attemptId] GROUP_ANCHOR_RECEIVED " +
                                    "kind=${anchor.kind} masterTimeNs=${anchor.masterTimeNs} " +
                                    "sampleIndex=${anchor.sampleIndex}"
                            )
                            audioPipeline.onPlaybackAnchor(anchor)
                        }
                        else -> onStatus(
                            "[DIAG $attemptId] CONTROL_FRAME_IGNORED " +
                                "messageType=0x${frame.messageType.toString(16)}"
                        )
                    }
                }
            } catch (error: Exception) {
                if (!closed.get()) {
                    onStatus(
                        "[DIAG $attemptId] CONTROL_READER_FAILURE " +
                            NetworkDiagnostics.exceptionDetails(error)
                    )
                    onStatus("Control channel failed: ${error.message ?: error.javaClass.simpleName}")
                    closeWithReason("control-reader-failure", attemptId)
                }
            }
        }, "syncinit-control-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun connectTcp(
        attemptId: String,
        channel: String,
        host: String,
        port: Int,
        readTimeoutMs: Int,
        network: Network
    ): TcpFrameConnection {
        val startedAt = SystemClock.elapsedRealtime()
        onStatus("[DIAG $attemptId] TCP_${channel}_CONNECT_BEGIN remote=$host:$port connectTimeoutMs=${PreviewConfig.TCP_CONNECT_TIMEOUT_MS} readTimeoutMs=$readTimeoutMs")
        return try {
            val connection = TcpFrameConnection.connect(
                host = host,
                port = port,
                connectTimeoutMs = PreviewConfig.TCP_CONNECT_TIMEOUT_MS,
                readTimeoutMs = readTimeoutMs,
                socketFactory = network.socketFactory
            )
            onStatus(
                "[DIAG $attemptId] TCP_${channel}_CONNECT_OK " +
                    "local=${connection.localEndpoint} remote=${connection.remoteEndpoint} " +
                    "connected=${connection.isConnected} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            connection
        } catch (error: Exception) {
            onStatus(
                "[DIAG $attemptId] TCP_${channel}_CONNECT_FAIL remote=$host:$port " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                    NetworkDiagnostics.exceptionDetails(error)
            )
            onStatus(NetworkDiagnostics.networkSnapshot(context, host))
            throw IOException(
                "$channel TCP connection to $host:$port failed (${NetworkDiagnostics.exceptionDetails(error)})",
                error
            )
        }
    }

    private inline fun <T> diagnosticStage(attemptId: String, stage: String, action: () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        onStatus("[DIAG $attemptId] STAGE_$stage BEGIN")
        return try {
            action().also {
                onStatus("[DIAG $attemptId] STAGE_$stage OK elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
        } catch (error: Exception) {
            onStatus(
                "[DIAG $attemptId] STAGE_$stage FAIL " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} " +
                    NetworkDiagnostics.exceptionDetails(error)
            )
            throw error
        }
    }

    private fun runClockSync(
        attemptId: String,
        control: TcpFrameConnection,
        masterHost: String,
        sessionId: Long,
        receiverId: Int,
        timePort: Int,
        audioPipeline: PcmAudioReceiver,
        network: Network
    ) {
        val address = InetAddress.getByName(masterHost)
        NativeTimeSyncClient.create(sessionId, receiverId).use { nativeClock ->
            openTimeSyncUdpClient(
                masterAddress = address,
                masterPort = timePort,
                receiveTimeoutMs = PreviewConfig.TIME_SYNC_RECEIVE_TIMEOUT_MS,
                network = network
            ).use { udp ->
                var probeAttempts = 0
                var lastMeasurement: com.syncinit.app.core.ClockSyncMeasurement? = null
                var consecutiveUnreachable = 0
                var timeoutCount = 0
                var invalidResponseCount = 0
                var staleResponseCount = 0
                onStatus(
                    "[DIAG $attemptId] UDP_TIME_SYNC_SOCKET_READY local=${udp.localAddress}:${udp.localPort} " +
                        "remote=${udp.remoteAddress}:${udp.remotePort} connected=${udp.isConnected} " +
                        "receiveTimeoutMs=${PreviewConfig.TIME_SYNC_RECEIVE_TIMEOUT_MS} packetBytes=${UdpTimeSyncClient.PACKET_SIZE} timestampMode=${udp.timestampMode}"
                )

                while (!closed.get()) {
                    nativeClock.expireRequestsBefore(System.nanoTime())
                    val t1Ns = System.nanoTime()
                    val request = nativeClock.makeRequest(t1Ns)
                    val requestSequence = UdpTimeSyncPacketMetadata.sequence(request)
                    probeAttempts += 1
                    var measurement: com.syncinit.app.core.ClockSyncMeasurement? = null
                    var requestPreparationUs = 0L
                    var udpSendCallUs = 0L

                    try {
                        val udpSendStartedAtNs = System.nanoTime()
                        requestPreparationUs = (udpSendStartedAtNs - t1Ns) / 1_000L
                        udp.send(request)
                        udpSendCallUs = (System.nanoTime() - udpSendStartedAtNs) / 1_000L

                        // A duplicated or delayed UDP response must never consume the
                        // request currently being measured. Drain stale datagrams until
                        // this request is answered or its original deadline expires.
                        val responseDeadlineNs =
                            t1Ns + PreviewConfig.TIME_SYNC_RECEIVE_TIMEOUT_MS * 1_000_000L
                        var responseAccepted = false
                        while (!closed.get() && !responseAccepted) {
                            val remainingNs = responseDeadlineNs - System.nanoTime()
                            if (remainingNs <= 0L) {
                                throw SocketTimeoutException("time-sync response deadline expired")
                            }
                            val receiveTimeoutMs = minOf(
                                PreviewConfig.TIME_SYNC_RECEIVE_TIMEOUT_MS.toLong(),
                                ((remainingNs + 999_999L) / 1_000_000L).coerceAtLeast(1L)
                            ).toInt()
                            val received = try {
                                udp.receiveTimed(receiveTimeoutMs)
                            } catch (error: FrameProtocolException) {
                                invalidResponseCount += 1
                                onStatus(
                                    "[DIAG $attemptId] UDP_MALFORMED_RESPONSE attempt=$probeAttempts " +
                                        "expectedSequence=$requestSequence invalidCount=$invalidResponseCount " +
                                        "${NetworkDiagnostics.exceptionDetails(error)}"
                                )
                                continue
                            }

                            val responseSequence = try {
                                UdpTimeSyncPacketMetadata.responseSequence(received.bytes)
                            } catch (error: IllegalArgumentException) {
                                invalidResponseCount += 1
                                onStatus(
                                    "[DIAG $attemptId] UDP_INVALID_RESPONSE_ENVELOPE attempt=$probeAttempts " +
                                        "expectedSequence=$requestSequence invalidCount=$invalidResponseCount " +
                                        "detail=${error.message ?: error.javaClass.simpleName}"
                                )
                                continue
                            }

                            if (responseSequence != requestSequence) {
                                staleResponseCount += 1
                                if (staleResponseCount <= 3 || staleResponseCount % 10 == 0) {
                                    onStatus(
                                        "[DIAG $attemptId] UDP_STALE_RESPONSE attempt=$probeAttempts " +
                                            "expectedSequence=$requestSequence responseSequence=$responseSequence " +
                                            "staleCount=$staleResponseCount"
                                    )
                                }
                                continue
                            }

                            val probeElapsedMs = (received.receivedAtNs - t1Ns) / 1_000_000.0
                            measurement = nativeClock.acceptResponse(received.bytes, received.receivedAtNs)
                            val t4ToAcceptUs = (System.nanoTime() - received.receivedAtNs) / 1_000L
                            if (measurement == null) {
                                invalidResponseCount += 1
                                val rejectionStatus = nativeClock.lastResponseStatus()
                                onStatus(
                                    "[DIAG $attemptId] UDP_RESPONSE_REJECTED attempt=$probeAttempts " +
                                        "expectedSequence=$requestSequence responseSequence=$responseSequence " +
                                        "elapsedMs=${"%.2f".format(probeElapsedMs)} t4ToAcceptUs=$t4ToAcceptUs " +
                                        "invalidCount=$invalidResponseCount reason=$rejectionStatus " +
                                        "responseBytes=${received.bytes.size} " +
                                        "source=${udp.remoteAddress}:${udp.remotePort}"
                                )
                                onStatus("Ignoring UDP time-sync response · $rejectionStatus.")
                                // Keep waiting for a valid response to this sequence.
                                // The outer timeout expires only this request, not later probes.
                                continue
                            }

                            consecutiveUnreachable = 0
                            lastMeasurement = measurement
                            val probe = nativeClock.lastAcceptedProbe()
                            if (probe == null) {
                                onStatus("[DIAG $attemptId] CLOCK_PROBE_METADATA_UNAVAILABLE attempt=$probeAttempts")
                            } else {
                                onStatus(
                                    "[DIAG $attemptId] CLOCK_PROBE sequence=${probe.sequence} " +
                                        "t1Ns=${probe.t1Ns} t2Ns=${probe.t2Ns} " +
                                        "t3Ns=${probe.t3Ns} t4Ns=${probe.t4Ns} " +
                                        "roundTripNs=${probe.roundTripNs} rawOffsetNs=${probe.rawOffsetNs} " +
                                        "requestPreparationUs=$requestPreparationUs udpSendCallUs=$udpSendCallUs " +
                                        "t4ToAcceptUs=$t4ToAcceptUs" +
                                        received.kernelTimestamp?.let { kernel ->
                                            " kernelT4Ns=" + kernel.monotonicNs +
                                                " kernelT4RealtimeNs=" + kernel.realtimeNs +
                                                " kernelT4MappingUncertaintyNs=" + kernel.mappingUncertaintyNs +
                                                " kernelT4CaptureLagUs=" + ((received.receivedAtNs - kernel.monotonicNs) / 1_000L)
                                        }.orEmpty()
                                )
                            }
                            onStatus(
                                "[DIAG $attemptId] CLOCK_FIT offsetMs=${"%.6f".format(measurement.offsetNs / 1_000_000.0)} " +
                                    "skewPpm=${measurement.receiverSkewPpm?.let { "%.3f".format(it) } ?: "unavailable"} " +
                                    "rttMedianMs=${"%.4f".format(measurement.rttMedianMs)} " +
                                    "residualRmsMs=${"%.6f".format(measurement.residualRmsMs)} " +
                                    "samplesUsed=${measurement.samplesUsed} samplesSeen=${measurement.samplesSeen} " +
                                    "selectedSpanMs=${measurement.selectedSampleSpanNs / 1_000_000L} " +
                                    "stableStreak=${measurement.stableStreak} " +
                                    "acquisitionStable=${measurement.acquisitionStable} " +
                                    "selection=lowest-rtt-25pct windowMs=60000"
                            )
                            if (probeAttempts <= PreviewConfig.JOIN_BURST_PROBES || probeAttempts % 10 == 0) {
                                onStatus("[DIAG $attemptId] UDP_PROBE_OK attempt=$probeAttempts elapsedMs=${"%.2f".format(probeElapsedMs)} rttMedianMs=${"%.2f".format(measurement.rttMedianMs)} offsetMs=${"%.3f".format(measurement.offsetNs / 1_000_000.0)}")
                            }
                            audioPipeline.updateClockMeasurement(measurement)
                            onStatus(
                                "Clock acquisition · probes $probeAttempts · " +
                                    "RTT p50 ${"%.2f".format(measurement.rttMedianMs)} ms · " +
                                    "span ${"%.1f".format(measurement.selectedSampleSpanNs / 1_000_000_000.0)} s · " +
                                    "residual ${"%.3f".format(measurement.residualRmsMs)} ms"
                            )
                            if (measurement.acquisitionStable) {
                                onStatus(
                                    "Clock estimator stable · offset ${"%.3f".format(measurement.offsetNs / 1_000_000.0)} ms · " +
                                        "skew ${"%.2f".format(measurement.receiverSkewPpm ?: 0.0)} ppm."
                                )
                            }
                            responseAccepted = true
                        }
                    } catch (error: PortUnreachableException) {
                        nativeClock.expireRequestsBefore(t1Ns + 1L)
                        consecutiveUnreachable += 1
                        onStatus("[DIAG $attemptId] UDP_PORT_UNREACHABLE attempt=$probeAttempts sequence=$requestSequence count=$consecutiveUnreachable target=${udp.remoteAddress}:${udp.remotePort} ${NetworkDiagnostics.exceptionDetails(error)}")
                        if (consecutiveUnreachable >= 5) {
                            throw IOException(
                                "Master UDP time-sync port $timePort is unreachable after 5 probes. " +
                                    "Check that both phones are on the same LAN and client isolation is disabled.",
                                error
                            )
                        }
                        onStatus(
                            "Master UDP time-sync port $timePort is temporarily unreachable " +
                                "($consecutiveUnreachable/5); retrying."
                        )
                    } catch (error: SocketTimeoutException) {
                        nativeClock.expireRequestsBefore(t1Ns + 1L)
                        timeoutCount += 1
                        if (timeoutCount <= 3 || timeoutCount % 10 == 0) {
                            onStatus("[DIAG $attemptId] UDP_PROBE_TIMEOUT attempt=$probeAttempts sequence=$requestSequence totalTimeouts=$timeoutCount timeoutMs=${PreviewConfig.TIME_SYNC_RECEIVE_TIMEOUT_MS} target=${udp.remoteAddress}:${udp.remotePort} ${NetworkDiagnostics.exceptionDetails(error)}")
                        }
                        onStatus("UDP time-sync response timed out; retrying.")
                    }
                    val stable = lastMeasurement?.acquisitionStable == true &&
                        (lastMeasurement?.stableStreak ?: 0) >= 3
                    val intervalMs = when {
                        probeAttempts <= PreviewConfig.JOIN_BURST_PROBES -> PreviewConfig.JOIN_BURST_INTERVAL_MS
                        stable -> PreviewConfig.STEADY_STATE_INTERVAL_MS
                        else -> PreviewConfig.UNSTABLE_RETRY_INTERVAL_MS
                    }
                    val remainingNs = t1Ns + intervalMs * 1_000_000L - System.nanoTime()
                    if (remainingNs > 0L) {
                        try {
                            Thread.sleep(remainingNs / 1_000_000L, (remainingNs % 1_000_000L).toInt())
                        } catch (_: InterruptedException) {
                            if (closed.get()) break
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            }
        }
    }

    override fun close() = closeWithReason("external-close")

    private fun closeWithReason(trigger: String, attemptId: String = "external") {
        if (!closed.compareAndSet(false, true)) return
        onStatus(
            "[DIAG $attemptId] SPEAKER_CLIENT_CLOSE trigger=$trigger " +
                "controlLocal=${controlConnection?.localEndpoint ?: "unknown"} " +
                "controlRemote=${controlConnection?.remoteEndpoint ?: "unknown"} " +
                "audioLocal=${audioConnection?.localEndpoint ?: "unknown"} " +
                "audioRemote=${audioConnection?.remoteEndpoint ?: "unknown"}"
        )
        closeResources()
        executor.shutdownNow()
    }

    private fun closeResources() {
        try { audioPipeline?.close() } catch (_: Exception) { }
        audioPipeline = null
        try { controlConnection?.close() } catch (_: Exception) { }
        try { audioConnection?.close() } catch (_: Exception) { }
        controlConnection = null
        audioConnection = null
    }

    private fun String.decodeSessionToken(): ByteArray {
        val normalized = trim()
        require(normalized.length == 32) { "session token must be exactly 32 hexadecimal characters" }
        require(normalized.all { it.digitToIntOrNull(16) != null }) { "session token contains a non-hexadecimal character" }
        return ByteArray(16) { index ->
            val high = normalized[index * 2].digitToInt(16)
            val low = normalized[index * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
    }
}
