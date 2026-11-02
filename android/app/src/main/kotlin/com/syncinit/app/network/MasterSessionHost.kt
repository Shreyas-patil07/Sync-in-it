package com.syncinit.app.network

import com.syncinit.app.core.NativeReceiverSession
import com.syncinit.app.core.ReceiverSessionConfig
import com.syncinit.app.core.ReceiverSessionResult
import com.syncinit.transport.TcpFrameConnection
import com.syncinit.transport.TcpFrameServer
import com.syncinit.transport.FrameProtocolException
import com.syncinit.transport.UdpTimeSyncServer
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class HostInvitation(
    val address: String,
    val controlPort: Int,
    val sessionToken: String
)

/** One-receiver Android master that performs control and audio-channel admission only. */
class MasterSessionHost(private val onStatus: (String) -> Unit) : Closeable {
    companion object {
        const val CONTROL_PORT = 47_800
        const val TIME_PORT = 47_802
        private const val AUDIO_READ_TIMEOUT_MS = 0
    }

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "syncinit-master-handshake").apply { isDaemon = true }
    }

    @Volatile private var controlServer: TcpFrameServer? = null
    @Volatile private var audioServer: TcpFrameServer? = null
    @Volatile private var timeSyncServer: UdpTimeSyncServer? = null
    @Volatile private var controlConnection: TcpFrameConnection? = null
    @Volatile private var audioConnection: TcpFrameConnection? = null
    @Volatile private var nativeSession: NativeReceiverSession? = null

    fun start(): HostInvitation {
        check(!closed.get()) { "host service is closed" }
        check(started.compareAndSet(false, true)) { "host service has already started" }

        var control: TcpFrameServer? = null
        var audio: TcpFrameServer? = null
        var session: NativeReceiverSession? = null
        try {
            val address = discoverLocalIpv4Address()
            val anyAddress = InetAddress.getByName("0.0.0.0")
            val audioListener = TcpFrameServer(anyAddress, 0, connectionReadTimeoutMs = AUDIO_READ_TIMEOUT_MS)
            audio = audioListener
            val controlListener = TcpFrameServer(anyAddress, CONTROL_PORT, connectionReadTimeoutMs = AUDIO_READ_TIMEOUT_MS)
            control = controlListener

            val random = SecureRandom()
            val token = ByteArray(16).also(random::nextBytes)
            val sessionId = random.nextInt().toLong() and 0xffff_ffffL
            session = NativeReceiverSession.create(
                ReceiverSessionConfig(
                    sessionId = sessionId,
                    receiverId = 1,
                    token = token,
                    audioPort = audioListener.localPort,
                    timePort = TIME_PORT
                )
            )

            controlServer = controlListener
            audioServer = audioListener
            nativeSession = session
            executor.execute(::serve)
            onStatus("Hosting on $address:$CONTROL_PORT. Waiting for a receiver.")
            return HostInvitation(address, controlListener.localPort, token.toHex())
        } catch (error: Exception) {
            session?.close()
            control?.close()
            audio?.close()
            closed.set(true)
            executor.shutdownNow()
            throw error
        }
    }

    private fun serve() {
        try {
            onStatus("Waiting for receiver HELLO on TCP $CONTROL_PORT…")
            val control = checkNotNull(controlServer).accept()
            controlConnection = control

            val hello = control.readFrame() ?: error("receiver closed before HELLO")
            check(hello.messageType == 0x01 && hello.flags == 0) { "first control frame must be HELLO" }
            val helloResult = checkNotNull(nativeSession).onHelloFrame(hello.toEncodedBytes())
            check(helloResult == ReceiverSessionResult.Accepted) { "HELLO rejected: $helloResult" }

            val welcome = checkNotNull(nativeSession?.welcomeFrame()) { "native WELCOME was not produced" }
            control.writeEncodedFrame(welcome)
            onStatus("HELLO accepted. Waiting for the separate audio connection…")

            val audio = checkNotNull(audioServer).accept()
            audioConnection = audio
            val audioHello = audio.readFrame() ?: error("receiver closed before AUDIO_HELLO")
            check(audioHello.messageType == 0x03 && audioHello.flags == 0) {
                "first audio-channel frame must be AUDIO_HELLO"
            }
            val audioResult = checkNotNull(nativeSession).onAudioHelloFrame(audioHello.toEncodedBytes())
            check(audioResult == ReceiverSessionResult.Accepted) { "AUDIO_HELLO rejected: $audioResult" }

            onStatus(
                "Receiver admitted · native state ${nativeSession?.state()} · starting UDP clock sync."
            )
            runTimeSyncServer(control)
        } catch (error: Exception) {
            if (!closed.get()) onStatus("Host handshake failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            closeResources()
        }
    }

    private fun runTimeSyncServer(control: TcpFrameConnection) {
        val server = UdpTimeSyncServer(
            bindAddress = InetAddress.getByName("0.0.0.0"),
            port = TIME_PORT,
            receiveTimeoutMs = 500
        )
        timeSyncServer = server

        val controlReader = Thread({ readControlFrames(control) }, "syncinit-master-control-reader").apply {
            isDaemon = true
            start()
        }
        var responseCount = 0
        try {
            while (!closed.get()) {
                val datagram = try {
                    server.receive()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: FrameProtocolException) {
                    continue
                } catch (error: SocketException) {
                    if (closed.get()) break
                    throw error
                }

                // The UDP peer must be the same IP that completed the TCP admission.
                if (datagram.source.address != control.remoteAddress) continue

                val t3Ns = System.nanoTime()
                val response = nativeSession?.timeSyncResponse(
                    datagram.bytes, datagram.receivedAtNs, t3Ns
                ) ?: continue
                server.sendTo(response, datagram.source)

                responseCount += 1
                if (responseCount == 1 || responseCount % 20 == 0) {
                    onStatus("UDP time sync active · $responseCount validated requests answered.")
                }
            }
        } finally {
            controlReader.interrupt()
        }
    }

    private fun readControlFrames(control: TcpFrameConnection) {
        try {
            while (!closed.get()) {
                val frame = control.readFrame() ?: break
                when (frame.messageType) {
                    0x04 -> {
                        check(frame.flags == 0) { "READY frame flags must be zero" }
                        val result = nativeSession?.onReadyFrame(frame.toEncodedBytes())
                        when (result) {
                            ReceiverSessionResult.Accepted ->
                                onStatus("Receiver READY · native state ${nativeSession?.state()}.")
                            ReceiverSessionResult.BufferInsufficient ->
                                onStatus("Clock sync is stable. Master is holding READY until an audio buffer exists.")
                            ReceiverSessionResult.ClockNotStable ->
                                onStatus("Receiver requested READY before the clock estimator was stable.")
                            ReceiverSessionResult.ClockResidualTooHigh ->
                                onStatus("Receiver READY rejected: clock residual exceeded the configured gate.")
                            else ->
                                onStatus("Receiver READY rejected: ${result ?: "session unavailable"}.")
                        }
                    }
                    0x0A -> {
                        onStatus("Receiver sent LEAVE.")
                        close()
                        return
                    }
                    else -> onStatus("Ignoring unsupported control message 0x${frame.messageType.toString(16)}.")
                }
            }
            if (!closed.get()) {
                onStatus("Receiver control connection closed.")
                close()
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                onStatus("Receiver control channel failed: ${error.message ?: error.javaClass.simpleName}")
                close()
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
        closeQuietly(controlConnection)
        closeQuietly(audioConnection)
        closeQuietly(controlServer)
        closeQuietly(audioServer)
        closeQuietly(timeSyncServer)
        nativeSession?.close()
        controlConnection = null
        audioConnection = null
        controlServer = null
        audioServer = null
        timeSyncServer = null
        nativeSession = null
    }

    private fun discoverLocalIpv4Address(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces()
            ?: throw SocketException("No network interfaces are available")
        var fallback: String? = null
        for (network in Collections.list(interfaces)) {
            if (!network.isUp || network.isLoopback) continue
            for (address in Collections.list(network.inetAddresses)) {
                if (address !is Inet4Address || address.isLoopbackAddress || !address.isSiteLocalAddress) continue
                val value = address.hostAddress ?: continue
                if (network.name.startsWith("wlan", ignoreCase = true)) return value
                if (fallback == null) fallback = value
            }
        }
        return fallback ?: throw SocketException("No usable local IPv4 address found; connect to Wi-Fi first")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xff) }

    private fun closeQuietly(resource: Closeable?) {
        try { resource?.close() } catch (_: Exception) { }
    }
}
