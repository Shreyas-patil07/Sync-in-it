package com.syncinit.app.network

import com.syncinit.app.core.NativeProtocol
import com.syncinit.app.core.NativeTimeSyncClient
import com.syncinit.transport.TcpFrameConnection
import com.syncinit.transport.FrameProtocolException
import com.syncinit.transport.UdpTimeSyncClient
import java.io.Closeable
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android receiver-side client for the control and separate audio admission channels. */
class ReceiverSessionClient(private val onStatus: (String) -> Unit) : Closeable {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_READ_TIMEOUT_MS = 10_000
        private const val TIME_SYNC_RECEIVE_TIMEOUT_MS = 500
        private const val JOIN_BURST_PROBES = 20
        private const val JOIN_BURST_INTERVAL_MS = 50L
        private const val UNSTABLE_RETRY_INTERVAL_MS = 500L
        private const val STEADY_STATE_INTERVAL_MS = 2_000L
        private const val ESTIMATOR_WINDOW_NS = 60_000_000_000L
    }

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "syncinit-receiver-handshake").apply { isDaemon = true }
    }

    @Volatile private var controlConnection: TcpFrameConnection? = null
    @Volatile private var audioConnection: TcpFrameConnection? = null

    fun connect(host: String, tokenHex: String) {
        check(!closed.get()) { "receiver client is closed" }
        check(started.compareAndSet(false, true)) { "receiver client has already started" }
        require(host.isNotBlank()) { "master address must not be blank" }
        val token = tokenHex.decodeSessionToken()

        executor.execute {
            try {
                onStatus("Connecting to master $host…")
                val control = TcpFrameConnection.connect(
                    host = host,
                    port = MasterSessionHost.CONTROL_PORT,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = HANDSHAKE_READ_TIMEOUT_MS
                )
                controlConnection = control
                control.writeEncodedFrame(NativeProtocol.encodeHelloFrame(token))

                val welcomeFrame = control.readFrame() ?: error("master closed before WELCOME")
                val welcome = NativeProtocol.decodeWelcomeFrame(welcomeFrame)

                onStatus("Master accepted HELLO. Opening audio channel…")
                val audio = TcpFrameConnection.connect(
                    host = host,
                    port = welcome.audioPort,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = HANDSHAKE_READ_TIMEOUT_MS
                )
                audioConnection = audio
                audio.writeEncodedFrame(NativeProtocol.encodeAudioHelloFrame(welcome))

                onStatus("Control and audio channels established. Starting UDP clock acquisition…")
                runClockSync(control, host, welcome.sessionId, welcome.receiverId, welcome.timePort)
            } catch (error: Exception) {
                if (!closed.get()) {
                    onStatus("Join failed: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                closeResources()
            }
        }
    }

    private fun runClockSync(
        control: TcpFrameConnection,
        masterHost: String,
        sessionId: Long,
        receiverId: Int,
        timePort: Int
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
                var readySent = false

                while (!closed.get()) {
                    nativeClock.expireRequestsBefore(System.nanoTime())
                    val t1Ns = System.nanoTime()
                    val request = nativeClock.makeRequest(t1Ns)
                    probeAttempts += 1
                    var measurement: com.syncinit.app.core.ClockSyncMeasurement? = null

                    try {
                        udp.send(request)
                        val received = udp.receiveTimed()
                        measurement = nativeClock.acceptResponse(received.bytes, received.receivedAtNs)
                        if (measurement == null) {
                            nativeClock.expireRequestsBefore(System.nanoTime() + 1L)
                            onStatus("Ignoring an invalid or uncorrelated UDP time-sync response.")
                        } else {
                            lastMeasurement = measurement
                            onStatus(
                                "Clock acquisition · probes $probeAttempts · " +
                                    "RTT p50 ${"%.2f".format(measurement.rttMedianMs)} ms · " +
                                    "span ${"%.1f".format(measurement.selectedSampleSpanNs / 1_000_000_000.0)} s · " +
                                    "residual ${"%.3f".format(measurement.residualRmsMs)} ms"
                            )
                            if (measurement.acquisitionStable && !readySent) {
                                val residualUs = (measurement.residualRmsMs * 1_000.0).toLong()
                                    .coerceIn(0L, 0xffff_ffffL)
                                control.writeEncodedFrame(
                                    NativeProtocol.encodeReadyFrame(
                                        bufferedMs = 0L,
                                        syncStable = true,
                                        residualStdUs = residualUs
                                    )
                                )
                                readySent = true
                                onStatus(
                                    "Clock estimator stable · offset ${"%.3f".format(measurement.offsetNs / 1_000_000.0)} ms · " +
                                        "skew ${"%.2f".format(measurement.receiverSkewPpm ?: 0.0)} ppm. " +
                                        "READY sent with 0 ms buffer; playback remains gated."
                                )
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        nativeClock.expireRequestsBefore(System.nanoTime() + 1L)
                        onStatus("UDP time-sync response timed out; retrying.")
                    } catch (_: FrameProtocolException) {
                        nativeClock.expireRequestsBefore(System.nanoTime() + 1L)
                        onStatus("Malformed UDP time-sync packet ignored.")
                    }

                    val stable = lastMeasurement?.acquisitionStable == true &&
                        (lastMeasurement?.stableStreak ?: 0) >= 3
                    val intervalMs = when {
                        probeAttempts < JOIN_BURST_PROBES -> JOIN_BURST_INTERVAL_MS
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
