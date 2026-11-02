package com.syncinit.app.network

import android.content.Context
import android.os.SystemClock
import com.syncinit.app.audio.PcmAudioReceiver
import com.syncinit.app.core.NativeProtocol
import com.syncinit.app.core.NativeTimeSyncClient
import com.syncinit.transport.TcpFrameConnection
import com.syncinit.transport.FrameProtocolException
import com.syncinit.transport.UdpTimeSyncClient
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
    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_READ_TIMEOUT_MS = 10_000
        private const val TIME_SYNC_RECEIVE_TIMEOUT_MS = 500
        private const val JOIN_BURST_PROBES = 20
        private const val JOIN_BURST_INTERVAL_MS = 50L
        private const val UNSTABLE_RETRY_INTERVAL_MS = 500L
        private const val STEADY_STATE_INTERVAL_MS = 2_000L
    }

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "syncinit-receiver-handshake").apply { isDaemon = true }
    }

    @Volatile private var controlConnection: TcpFrameConnection? = null
    @Volatile private var audioConnection: TcpFrameConnection? = null
    @Volatile private var audioPipeline: PcmAudioReceiver? = null

    fun connect(host: String, tokenHex: String, controlPort: Int = MasterSessionHost.CONTROL_PORT) {
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
                onStatus("[DIAG $attemptId] STAGE_CONTROL_TCP_CONNECT_BEGIN target=$host:$controlPort timeoutMs=$CONNECT_TIMEOUT_MS")
                val control = connectTcp(attemptId, "CONTROL", host, controlPort, HANDSHAKE_READ_TIMEOUT_MS)
                controlConnection = control
                diagnosticStage(attemptId, "CONTROL_HELLO_WRITE") {
                    control.writeEncodedFrame(NativeProtocol.encodeHelloFrame(token))
                }

                onStatus("[DIAG $attemptId] STAGE_WELCOME_READ_BEGIN timeoutMs=$HANDSHAKE_READ_TIMEOUT_MS")
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

                onStatus("Master accepted HELLO. Opening audio channel…")
                val audio = connectTcp(attemptId, "AUDIO", host, welcome.audioPort, 0)
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
                                onStatus("[DIAG $attemptId] AUDIO_PIPELINE_FAILURE ${NetworkDiagnostics.exceptionDetails(error)}")
                                onStatus("Audio receiver failed: ${error.message ?: error.javaClass.simpleName}")
                                close()
                            }
                        }
                    )
                }
                audioPipeline = pipeline
                diagnosticStage(attemptId, "AUDIO_PIPELINE_START") { pipeline.start() }

                onStatus("[DIAG $attemptId] JOIN_TRANSPORT_READY control=${control.localEndpoint}->${control.remoteEndpoint} audio=${audio.localEndpoint}->${audio.remoteEndpoint}")
                onStatus("Control and audio channels established. Receiving PCM16 while acquiring the master clock…")
                runClockSync(
                    control,
                    host,
                    welcome.sessionId,
                    welcome.receiverId,
                    welcome.timePort,
                    pipeline
                )
            } catch (error: Exception) {
                if (!closed.get()) {
                    onStatus("[DIAG $attemptId] JOIN_FAILED ${NetworkDiagnostics.exceptionDetails(error)}")
                    onStatus("[DIAG $attemptId] NETWORK_AT_FAILURE\n${NetworkDiagnostics.networkSnapshot(context, host)}")
                    onStatus("Join failed: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                closeResources()
            }
        }
    }

    private fun connectTcp(
        attemptId: String,
        channel: String,
        host: String,
        port: Int,
        readTimeoutMs: Int
    ): TcpFrameConnection {
        val startedAt = SystemClock.elapsedRealtime()
        onStatus("[DIAG $attemptId] TCP_${channel}_CONNECT_BEGIN remote=$host:$port connectTimeoutMs=$CONNECT_TIMEOUT_MS readTimeoutMs=$readTimeoutMs")
        return try {
            val connection = TcpFrameConnection.connect(
                host = host,
                port = port,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = readTimeoutMs
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
        control: TcpFrameConnection,
        masterHost: String,
        sessionId: Long,
        receiverId: Int,
        timePort: Int,
        audioPipeline: PcmAudioReceiver
    ) {
        val address = InetAddress.getByName(masterHost)
        NativeTimeSyncClient.create(sessionId, receiverId).use { nativeClock ->
            UdpTimeSyncClient(
                masterAddress = address,
                masterPort = timePort,
                receiveTimeoutMs = TIME_SYNC_RECEIVE_TIMEOUT_MS
            ).use { udp ->
                var probeAttempts = 0
                var lastMeasurement: com.syncinit.app.core.ClockSyncMeasurement? = null
                var consecutiveUnreachable = 0
                var timeoutCount = 0
                var invalidResponseCount = 0
                onStatus(
                    "[DIAG] UDP_TIME_SYNC_SOCKET_READY local=${udp.localAddress}:${udp.localPort} " +
                        "remote=${udp.remoteAddress}:${udp.remotePort} connected=${udp.isConnected} " +
                        "receiveTimeoutMs=$TIME_SYNC_RECEIVE_TIMEOUT_MS packetBytes=${UdpTimeSyncClient.PACKET_SIZE}"
                )

                while (!closed.get()) {
                    nativeClock.expireRequestsBefore(System.nanoTime())
                    val t1Ns = System.nanoTime()
                    val request = nativeClock.makeRequest(t1Ns)
                    probeAttempts += 1
                    var measurement: com.syncinit.app.core.ClockSyncMeasurement? = null

                    try {
                        udp.send(request)
                        val received = udp.receiveTimed()
                        val probeElapsedMs = (received.receivedAtNs - t1Ns) / 1_000_000.0
                        measurement = nativeClock.acceptResponse(received.bytes, received.receivedAtNs)
                        if (measurement == null) {
                            invalidResponseCount += 1
                            nativeClock.expireRequestsBefore(System.nanoTime() + 1L)
                            onStatus("[DIAG] UDP_RESPONSE_REJECTED attempt=$probeAttempts elapsedMs=${"%.2f".format(probeElapsedMs)} invalidCount=$invalidResponseCount source=${udp.remoteAddress}:${udp.remotePort}")
                            onStatus("Ignoring an invalid or uncorrelated UDP time-sync response.")
                        } else {
                            consecutiveUnreachable = 0
                            lastMeasurement = measurement
                            if (probeAttempts <= JOIN_BURST_PROBES || probeAttempts % 10 == 0) {
                                onStatus("[DIAG] UDP_PROBE_OK attempt=$probeAttempts elapsedMs=${"%.2f".format(probeElapsedMs)} rttSampleMs=${"%.2f".format(measurement.rttMs)} offsetMs=${"%.3f".format(measurement.offsetNs / 1_000_000.0)}")
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
                        }
                    } catch (error: PortUnreachableException) {
                        nativeClock.expireRequestsBefore(System.nanoTime() + 1L)
                        consecutiveUnreachable += 1
                        onStatus("[DIAG] UDP_PORT_UNREACHABLE attempt=$probeAttempts count=$consecutiveUnreachable target=${udp.remoteAddress}:${udp.remotePort} ${NetworkDiagnostics.exceptionDetails(error)}")
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
                        nativeClock.expireRequestsBefore(System.nanoTime() + 1L)
                        timeoutCount += 1
                        if (timeoutCount <= 3 || timeoutCount % 10 == 0) {
                            onStatus("[DIAG] UDP_PROBE_TIMEOUT attempt=$probeAttempts totalTimeouts=$timeoutCount timeoutMs=$TIME_SYNC_RECEIVE_TIMEOUT_MS target=${udp.remoteAddress}:${udp.remotePort} ${NetworkDiagnostics.exceptionDetails(error)}")
                        }
                        onStatus("UDP time-sync response timed out; retrying.")
                    } catch (error: FrameProtocolException) {
                        nativeClock.expireRequestsBefore(System.nanoTime() + 1L)
                        invalidResponseCount += 1
                        onStatus("[DIAG] UDP_MALFORMED_RESPONSE attempt=$probeAttempts invalidCount=$invalidResponseCount ${NetworkDiagnostics.exceptionDetails(error)}")
                        onStatus("Malformed UDP time-sync packet ignored.")
                    }

                    val stable = lastMeasurement?.acquisitionStable == true &&
                        (lastMeasurement?.stableStreak ?: 0) >= 3
                    val intervalMs = when {
                        probeAttempts <= JOIN_BURST_PROBES -> JOIN_BURST_INTERVAL_MS
                        stable -> STEADY_STATE_INTERVAL_MS
                        else -> UNSTABLE_RETRY_INTERVAL_MS
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

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeResources()
            executor.shutdownNow()
        }
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
