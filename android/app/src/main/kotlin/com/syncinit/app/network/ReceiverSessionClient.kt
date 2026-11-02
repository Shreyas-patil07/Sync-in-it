package com.syncinit.app.network

import com.syncinit.app.core.NativeProtocol
import com.syncinit.transport.TcpFrameConnection
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android receiver-side client for the control and separate audio admission channels. */
class ReceiverSessionClient(private val onStatus: (String) -> Unit) : Closeable {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_READ_TIMEOUT_MS = 10_000
    }

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val finish = CountDownLatch(1)
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

                onStatus(
                    "Joined as receiver ${welcome.receiverId} · " +
                        "${welcome.sampleRateHz} Hz · control and audio channels established. " +
                        "Waiting for UDP time sync."
                )
                finish.await()
            } catch (error: Exception) {
                if (!closed.get()) {
                    onStatus("Join failed: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                closeResources()
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            finish.countDown()
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
