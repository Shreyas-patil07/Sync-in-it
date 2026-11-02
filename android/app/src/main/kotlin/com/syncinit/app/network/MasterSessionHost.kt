package com.syncinit.app.network

import android.content.Context
import android.os.Build
import com.syncinit.app.core.NativeReceiverSession
import com.syncinit.app.core.ReceiverSessionConfig
import com.syncinit.app.core.ReceiverSessionResult
import com.syncinit.transport.TcpFrameConnection
import com.syncinit.transport.TcpFrameServer
import com.syncinit.transport.FrameProtocolException
import com.syncinit.transport.UdpTimeSyncServer
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class HostInvitation(
    val serviceName: String,
    val controlPort: Int
)

/** One-receiver Android master that performs control and audio-channel admission only. */
class MasterSessionHost(context: Context, private val onStatus: (String) -> Unit) : Closeable {
    companion object {
        const val CONTROL_PORT = 47_800
        const val TIME_PORT = 47_802
        private const val AUDIO_READ_TIMEOUT_MS = 0
    }

    private val appContext = context.applicationContext
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
    @Volatile private var nativeSessionConfig: ReceiverSessionConfig? = null
    @Volatile private var advertiser: LanSessionAdvertiser? = null

    fun start(): HostInvitation {
        check(!closed.get()) { "host service is closed" }
        check(started.compareAndSet(false, true)) { "host service has already started" }

        var control: TcpFrameServer? = null
        var audio: TcpFrameServer? = null
        var time: UdpTimeSyncServer? = null
        var session: NativeReceiverSession? = null
        try {
            val anyAddress = InetAddress.getByName("0.0.0.0")
            val audioListener = TcpFrameServer(anyAddress, 0, connectionReadTimeoutMs = AUDIO_READ_TIMEOUT_MS)
            audio = audioListener
            val controlListener = TcpFrameServer(anyAddress, CONTROL_PORT, connectionReadTimeoutMs = AUDIO_READ_TIMEOUT_MS)
            control = controlListener
            // Bind UDP before advertising to avoid a receiver racing the time-sync listener.
            val timeListener = UdpTimeSyncServer(anyAddress, TIME_PORT, receiveTimeoutMs = 500)
            time = timeListener

            val random = SecureRandom()
            val token = ByteArray(16).also(random::nextBytes)
            val sessionId = random.nextInt().toLong() and 0xffff_ffffL
            val config = ReceiverSessionConfig(
                sessionId = sessionId,
                receiverId = 1,
                token = token.copyOf(),
                audioPort = audioListener.localPort,
                timePort = TIME_PORT
            )
            session = NativeReceiverSession.create(config)

            check(!closed.get()) { "host session was stopped while starting" }
            val deviceLabel = Build.MODEL.trim().take(32).ifBlank { "Android" }
            val serviceName = "Sync In It - $deviceLabel ${token.toHex().take(4)}"
            val serviceAdvertiser = LanSessionAdvertiser(appContext, onStatus)
            serviceAdvertiser.start(serviceName, controlListener.localPort, token.toHex())

            controlServer = controlListener
            audioServer = audioListener
            timeSyncServer = timeListener
            nativeSession = session
            nativeSessionConfig = config
            advertiser = serviceAdvertiser
            executor.execute(::serve)
            onStatus("Hosting and advertising on this Wi-Fi network. Waiting for a receiver.")
            return HostInvitation(serviceName, controlListener.localPort)
        } catch (error: Exception) {
            session?.close()
            control?.close()
            audio?.close()
            time?.close()
            closed.set(true)
            executor.shutdownNow()
            throw error
        }
    private fun serve() {
        var admitted = false
        try {
            while (!closed.get()) {
                var control: TcpFrameConnection? = null
                var audio: TcpFrameConnection? = null
                try {
                    onStatus("Waiting for receiver HELLO on TCP $CONTROL_PORT…")
                    control = checkNotNull(controlServer).accept()
                    controlConnection = control

                    val hello = control.readFrame() ?: error("receiver closed before HELLO")
                    check(hello.messageType == 0x01 && hello.flags == 0) {
                        "first control frame must be HELLO"
                    }
                    val currentSession = checkNotNull(nativeSession)
                    val helloResult = currentSession.onHelloFrame(hello.toEncodedBytes())
                    check(helloResult == ReceiverSessionResult.Accepted) {
                        "HELLO rejected: $helloResult"
                    }

                    val welcome = checkNotNull(currentSession.welcomeFrame()) {
                        "native WELCOME was not produced"
                    }
                    control.writeEncodedFrame(welcome)
                    onStatus("HELLO accepted. Waiting for the separate audio connection…")

                    audio = checkNotNull(audioServer).accept()
                    audioConnection = audio
                    check(audio.remoteAddress == control.remoteAddress) {
                        "audio TCP peer does not match the admitted control peer"
                    }
                    val audioHello = audio.readFrame() ?: error("receiver closed before AUDIO_HELLO")
                    check(audioHello.messageType == 0x03 && audioHello.flags == 0) {
                        "first audio-channel frame must be AUDIO_HELLO"
                    }
                    val audioResult = currentSession.onAudioHelloFrame(audioHello.toEncodedBytes())
                    check(audioResult == ReceiverSessionResult.Accepted) {
                        "AUDIO_HELLO rejected: $audioResult"
                    }

                    admitted = true
                    onStatus(
                        "Receiver admitted · native state ${currentSession.state()} · starting UDP clock sync."
                    )
                    runTimeSyncServer(control)
                    return
                } catch (error: Exception) {
                    if (closed.get()) break
                    if (admitted) {
                        onStatus("Admitted receiver disconnected: ${error.message ?: error.javaClass.simpleName}")
                        break
                    }

                    onStatus(
                        "Receiver join attempt failed: ${error.message ?: error.javaClass.simpleName}. " +
                            "Host remains available; retry from the receiver."
                    )
                    closeQuietly(control)
                    closeQuietly(audio)
                    if (controlConnection === control) controlConnection = null
                    if (audioConnection === audio) audioConnection = null

                    nativeSession?.close()
                    val config = nativeSessionConfig
                    if (config == null) {
                        onStatus("Host session could not reset after a failed join attempt.")
                        break
                    }
                    nativeSession = try {
                        NativeReceiverSession.create(config.copy(token = config.token.copyOf()))
                    } catch (resetError: Exception) {
                        onStatus("Host session reset failed: ${resetError.message ?: resetError.javaClass.simpleName}")
                        break
                    }
                }
            }
        } finally {
            if (closed.compareAndSet(false, true)) executor.shutdownNow()
            closeResources()
        }
    }

    private fun runTimeSyncServer(control: TcpFrameConnection) {
        val server = checkNotNull(timeSyncServer) { "UDP time-sync listener was not started" }

        val controlReader = Thread({ readControlFrames(control) }, "syncinit-master-control-reader").apply {
            isDaemon = true
            start()
        }
        var responseCount = 0
        var admittedUdpPeer: InetSocketAddress? = null
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

                // The UDP peer must match the admitted TCP peer, including its client port.
                if (datagram.source.address != control.remoteAddress) continue
                val pinnedPeer = admittedUdpPeer
                if (pinnedPeer != null && datagram.source != pinnedPeer) continue

                val t3Ns = System.nanoTime()
                val response = nativeSession?.timeSyncResponse(
                    datagram.bytes, datagram.receivedAtNs, t3Ns
                ) ?: continue
                if (admittedUdpPeer == null) admittedUdpPeer = datagram.source
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
        closeQuietly(advertiser)
        nativeSession?.close()
        controlConnection = null
        audioConnection = null
        controlServer = null
        audioServer = null
        timeSyncServer = null
        advertiser = null
        nativeSession = null
        nativeSessionConfig = null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xff) }

    private fun closeQuietly(resource: Closeable?) {
        try { resource?.close() } catch (_: Exception) { }
    }
}
