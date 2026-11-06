// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.network

import com.syncinit.app.PreviewConfig

import android.content.Context
import android.os.Build
import com.syncinit.app.audio.NativePcmJitterBuffer
import com.syncinit.app.core.NativeProtocol
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

data class HostInvitation(
    val serviceName: String,
    val controlPort: Int
)

private data class StreamCursor(
    val nextSampleIndex: Long,
    val nextChunkDueNs: Long
)

/** Android master that admits several independent receivers into one shared audio session. */
class MasterSessionHost(context: Context, private val onStatus: (String) -> Unit) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val nextReceiverId = AtomicInteger(1)
    private val receivers = ConcurrentHashMap<Int, HostReceiverPeer>()
    private val groupStartLock = Any()
    private val groupStartScheduled = AtomicBoolean(false)
    private val groupStartCheckQueued = AtomicBoolean(false)
    private val streamCursor = AtomicReference<StreamCursor?>(null)
    @Volatile private var firstReceiverAdmissionNs = 0L
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "syncinit-master-admission").apply { isDaemon = true }
    }

    @Volatile private var controlServer: TcpFrameServer? = null
    @Volatile private var audioServer: TcpFrameServer? = null
    @Volatile private var timeSyncServer: UdpTimeSyncServer? = null
    @Volatile private var nativeSessionConfig: ReceiverSessionConfig? = null
    @Volatile private var advertiser: LanSessionAdvertiser? = null
    @Volatile private var audioSenderThread: Thread? = null
    @Volatile private var timeSyncThread: Thread? = null

    fun start(): HostInvitation {
        check(!closed.get()) { "host service is closed" }
        check(started.compareAndSet(false, true)) { "host service has already started" }

        var control: TcpFrameServer? = null
        var audio: TcpFrameServer? = null
        var time: UdpTimeSyncServer? = null
        try {
            val anyAddress = InetAddress.getByName("0.0.0.0")
            val audioListener = TcpFrameServer(
                anyAddress,
                0,
                acceptTimeoutMs = PreviewConfig.AUDIO_ACCEPT_TIMEOUT_MS,
                connectionReadTimeoutMs = 10_000
            )
            audio = audioListener
            val controlListener = TcpFrameServer(anyAddress, PreviewConfig.CONTROL_PORT, connectionReadTimeoutMs = PreviewConfig.AUDIO_READ_TIMEOUT_MS)
            control = controlListener
            // Bind UDP before advertising to avoid a receiver racing the time-sync listener.
            val timeListener = UdpTimeSyncServer(anyAddress, PreviewConfig.TIME_PORT, receiveTimeoutMs = 500)
            time = timeListener

            val random = SecureRandom()
            val token = ByteArray(16).also(random::nextBytes)
            val sessionId = random.nextInt().toLong() and 0xffff_ffffL
            val config = ReceiverSessionConfig(
                sessionId = sessionId,
                receiverId = 0,
                token = token.copyOf(),
                audioPort = audioListener.localPort,
                timePort = PreviewConfig.TIME_PORT
            )

            check(!closed.get()) { "host session was stopped while starting" }
            check(controlListener.isListening) { "control TCP listener did not bind" }
            check(audioListener.isListening) { "audio TCP listener did not bind" }
            onStatus(
                "DIAG HOST_LISTENERS_BOUND control=${controlListener.localBindAddress}:${controlListener.localPort} " +
                    "audio=${audioListener.localBindAddress}:${audioListener.localPort} udp=0.0.0.0:${PreviewConfig.TIME_PORT}"
            )
            onStatus(NetworkDiagnostics.networkSnapshot(appContext))
            controlServer = controlListener
            audioServer = audioListener
            timeSyncServer = timeListener
            nativeSessionConfig = config

            // These loops are independent: admission accepts new TCP pairs while UDP serves every
            // admitted receiver, and the shared audio producer fans identical chunks out to peers.
            executor.execute(::serve)
            startTimeSyncServer()

            val deviceLabel = Build.MODEL.trim().take(32).ifBlank { "Android" }
            val serviceName = "Sync In It - $deviceLabel ${token.toHex().take(4)}"
            val serviceAdvertiser = LanSessionAdvertiser(appContext, onStatus)
            advertiser = serviceAdvertiser
            serviceAdvertiser.start(serviceName, controlListener.localPort, token.toHex())

            onStatus("DIAG HOST_READY controlPort=${controlListener.localPort} audioPort=${audioListener.localPort} timePort=${PreviewConfig.TIME_PORT} controlListening=${controlListener.isListening} audioListening=${audioListener.isListening}")
            onStatus("TCP listener ready on port ${controlListener.localPort}. Advertising a session for up to ${PreviewConfig.MAX_SPEAKERS} speakers.")
            onStatus("Waiting for speakers · 0/${PreviewConfig.MAX_SPEAKERS} connected.")
            return HostInvitation(serviceName, controlListener.localPort)
        } catch (error: Exception) {
            onStatus("DIAG HOST_START_FAILED ${NetworkDiagnostics.exceptionDetails(error)}")
            onStatus("DIAG HOST_NETWORK_AT_FAILURE\n${NetworkDiagnostics.networkSnapshot(appContext)}")
            closed.set(true)
            executor.shutdownNow()
            timeSyncThread?.interrupt()
            closeQuietly(control)
            closeQuietly(audio)
            closeQuietly(time)
            closeResources()
            throw error
        }
    }

    /**
     * Admits one control/audio pair at a time. A WELCOME is sent before accepting that receiver's
     * audio socket, so the ordering of control handshakes also establishes audio-socket ownership.
     * Once admitted, the receiver's reader/writer and clock state are independent of this loop.
     */
    private fun serve() {
        try {
            while (!closed.get()) {
                var control: TcpFrameConnection? = null
                var audio: TcpFrameConnection? = null
                var session: NativeReceiverSession? = null
                var ownershipTransferred = false
                try {
                    val listeningControl = checkNotNull(controlServer)
                    if (groupStartScheduled.get()) {
                        val rejected = listeningControl.accept()
                        onStatus("Host is already playing this group · restart hosting to add more speakers.")
                        closeQuietly(rejected)
                        continue
                    }
                    val admissionStartedAt = firstReceiverAdmissionNs
                    if (admissionStartedAt != 0L &&
                        System.nanoTime() - admissionStartedAt >= PreviewConfig.GROUP_ADMISSION_WINDOW_NS) {
                        val rejected = listeningControl.accept()
                        onStatus("Group admission window closed · restart hosting to add another speaker.")
                        closeQuietly(rejected)
                        continue
                    }
                    if (receivers.size >= PreviewConfig.MAX_SPEAKERS) {
                        val rejected = listeningControl.accept()
                        onStatus("Host is full · rejected another connection (limit ${PreviewConfig.MAX_SPEAKERS}).")
                        closeQuietly(rejected)
                        continue
                    }

                    onStatus("Waiting for Speaker HELLO · ${receivers.size}/${PreviewConfig.MAX_SPEAKERS} connected.")
                    val acceptedControl = listeningControl.accept()
                    control = acceptedControl
                    onStatus("DIAG HOST_CONTROL_ACCEPTED local=${acceptedControl.localEndpoint} peer=${acceptedControl.remoteEndpoint}")

                    val hello = acceptedControl.readFrame() ?: error("receiver closed before HELLO")
                    onStatus("DIAG HOST_HELLO_RECEIVED peer=${acceptedControl.remoteEndpoint} messageType=0x${hello.messageType.toString(16)} flags=${hello.flags} payloadBytes=${hello.payload.size}")
                    check(hello.messageType == 0x01 && hello.flags == 0) {
                        "first control frame must be HELLO"
                    }

                    val receiverId = nextReceiverId.getAndIncrement()
                    check(receiverId in 1..0xffff) { "receiver ID space is exhausted" }
                    val baseConfig = checkNotNull(nativeSessionConfig)
                    val receiverConfig = baseConfig.copy(
                        receiverId = receiverId,
                        token = baseConfig.token.copyOf()
                    )
                    val receiverSession = NativeReceiverSession.create(receiverConfig)
                    session = receiverSession
                    val helloResult = receiverSession.onHelloFrame(hello.toEncodedBytes())
                    check(helloResult == ReceiverSessionResult.Accepted) {
                        "HELLO rejected: $helloResult"
                    }

                    val welcome = checkNotNull(receiverSession.welcomeFrame()) {
                        "native WELCOME was not produced"
                    }
                    acceptedControl.writeEncodedFrame(welcome)
                    onStatus("HELLO accepted for receiverId=$receiverId. Waiting for its separate audio connection…")

                    val acceptedAudio = checkNotNull(audioServer).accept()
                    audio = acceptedAudio
                    onStatus("DIAG HOST_AUDIO_ACCEPTED receiverId=$receiverId local=${acceptedAudio.localEndpoint} peer=${acceptedAudio.remoteEndpoint}")
                    check(acceptedAudio.remoteAddress == acceptedControl.remoteAddress) {
                        "audio TCP peer does not match the admitted control peer"
                    }

                    val audioHello = acceptedAudio.readFrame() ?: error("receiver closed before AUDIO_HELLO")
                    onStatus("DIAG HOST_AUDIO_HELLO_RECEIVED receiverId=$receiverId peer=${acceptedAudio.remoteEndpoint} messageType=0x${audioHello.messageType.toString(16)} flags=${audioHello.flags} payloadBytes=${audioHello.payload.size}")
                    check(audioHello.messageType == 0x03 && audioHello.flags == 0) {
                        "first audio-channel frame must be AUDIO_HELLO"
                    }
                    val audioResult = receiverSession.onAudioHelloFrame(audioHello.toEncodedBytes())
                    check(audioResult == ReceiverSessionResult.Accepted) {
                        "AUDIO_HELLO rejected: $audioResult"
                    }

                    val peer = HostReceiverPeer(receiverId, receiverSession, acceptedControl, acceptedAudio)
                    synchronized(groupStartLock) {
                        check(!groupStartScheduled.get()) {
                            "group playback started while this Speaker was joining"
                        }
                        val admissionWindowStartedAt = firstReceiverAdmissionNs
                        check(admissionWindowStartedAt == 0L ||
                            System.nanoTime() - admissionWindowStartedAt < PreviewConfig.GROUP_ADMISSION_WINDOW_NS) {
                            "group admission window closed while this Speaker was joining"
                        }
                        check(receivers.putIfAbsent(receiverId, peer) == null) {
                            "receiver ID $receiverId is already active"
                        }
                        if (receivers.size == 1) {
                            firstReceiverAdmissionNs = System.nanoTime()
                            onStatus("Group admission window open · add up to ${PreviewConfig.MAX_SPEAKERS} speakers within 15 seconds.")
                            scheduleGroupReadinessTimeout(firstReceiverAdmissionNs)
                        }
                        ownershipTransferred = true
                    }
                    control = null
                    audio = null
                    session = null

                    peer.startAudioWriter()
                    startControlReader(peer)
                    onStatus(
                        "Speaker connected · receiverId=$receiverId · native state ${peer.session.state()} · " +
                            "${receivers.size}/${PreviewConfig.MAX_SPEAKERS} connected."
                    )
                    startAudioTestStream(baseConfig.sessionId)
                } catch (error: SocketException) {
                    if (closed.get()) break
                    reportJoinFailure(control, error)
                } catch (error: SocketTimeoutException) {
                    if (!closed.get()) reportJoinFailure(control, error)
                } catch (error: Exception) {
                    if (!closed.get()) reportJoinFailure(control, error)
                } finally {
                    if (!ownershipTransferred) {
                        closeQuietly(control)
                        closeQuietly(audio)
                        closeQuietly(session)
                    }
                }
            }
        } finally {
            if (closed.get()) closeResources()
        }
    }

    private fun reportJoinFailure(control: TcpFrameConnection?, error: Exception) {
        onStatus("DIAG HOST_JOIN_ATTEMPT_FAILED peer=${control?.remoteEndpoint ?: "unknown"} ${NetworkDiagnostics.exceptionDetails(error)}")
        onStatus("DIAG HOST_NETWORK_AT_JOIN_FAILURE\n${NetworkDiagnostics.networkSnapshot(appContext)}")
        onStatus(
            "Speaker join attempt failed: ${error.message ?: error.javaClass.simpleName}. " +
                "Host remains available; retry from the Speaker."
        )
    }

    private inner class HostReceiverPeer(
        val receiverId: Int,
        val session: NativeReceiverSession,
        val control: TcpFrameConnection,
        val audio: TcpFrameConnection
    ) : Closeable {
        private val peerClosed = AtomicBoolean(false)
        private val audioQueue = ArrayBlockingQueue<ByteArray>(PreviewConfig.AUDIO_QUEUE_CHUNKS)
        private val audioFramesQueued = AtomicLong(0L)
        private val audioFramesWritten = AtomicLong(0L)
        private val audioQueueHighWater = AtomicInteger(0)
        val udpPeer = AtomicReference<InetSocketAddress?>(null)
        private var audioWriter: Thread? = null

        fun startAudioWriter() {
            check(audioWriter == null) { "receiver audio writer has already started" }
            val writer = Thread({
                try {
                    while (!peerClosed.get() && !closed.get()) {
                        val frame = audioQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        audio.writeEncodedFrame(frame)
                        val written = audioFramesWritten.incrementAndGet()
                        if (written == 1L || written % 250L == 0L) {
                            onStatus(
                                "DIAG HOST_AUDIO_DELIVERY receiverId=$receiverId " +
                                    "queued=${audioFramesQueued.get()} written=$written " +
                                    "queueDepth=${audioQueue.size} queueHighWater=${audioQueueHighWater.get()}"
                            )
                        }
                    }
                } catch (_: InterruptedException) {
                    if (!peerClosed.get() && !closed.get()) Thread.currentThread().interrupt()
                } catch (error: Exception) {
                    if (!peerClosed.get() && !closed.get()) {
                        onStatus("DIAG HOST_AUDIO_WRITER_FAILED receiverId=$receiverId ${NetworkDiagnostics.exceptionDetails(error)}")
                        removeReceiver(this, "audio channel failed: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }, "syncinit-audio-writer-$receiverId").apply { isDaemon = true }
            audioWriter = writer
            writer.start()
        }

        fun offerAudio(frame: ByteArray): Boolean {
            if (peerClosed.get() || !audioQueue.offer(frame)) return false
            audioFramesQueued.incrementAndGet()
            val depth = audioQueue.size
            audioQueueHighWater.updateAndGet { previous -> maxOf(previous, depth) }
            return true
        }

        override fun close() {
            if (!peerClosed.compareAndSet(false, true)) return
            audioQueue.clear()
            audioWriter?.interrupt()
            audioWriter = null
            closeQuietly(audio)
            closeQuietly(control)
            closeQuietly(session)
        }
    }

    private fun startControlReader(peer: HostReceiverPeer) {
        Thread({ readControlFrames(peer) }, "syncinit-control-reader-${peer.receiverId}").apply {
            isDaemon = true
            start()
        }
    }

    private fun readControlFrames(peer: HostReceiverPeer) {
        var reason = "control channel closed"
        try {
            while (!closed.get()) {
                val frame = peer.control.readFrame() ?: break
                when (frame.messageType) {
                    0x04 -> {
                        check(frame.flags == 0) { "READY frame flags must be zero" }
                        val result = peer.session.onReadyFrame(frame.toEncodedBytes())
                        when (result) {
                            ReceiverSessionResult.Accepted -> {
                                onStatus("Speaker READY · receiverId=${peer.receiverId} · native state ${peer.session.state()}.")
                                tryScheduleGroupStart()
                            }
                            ReceiverSessionResult.BufferInsufficient ->
                                onStatus("Speaker ${peer.receiverId}: clock is stable; waiting for sufficient audio buffer.")
                            ReceiverSessionResult.ClockNotStable ->
                                onStatus("Speaker ${peer.receiverId}: READY arrived before clock stability.")
                            ReceiverSessionResult.ClockResidualTooHigh ->
                                onStatus("Speaker ${peer.receiverId}: READY rejected because clock residual exceeded the configured gate.")
                            else ->
                                onStatus("Speaker ${peer.receiverId}: READY rejected · $result.")
                        }
                    }
                    0x0A -> {
                        reason = "Speaker sent LEAVE"
                        break
                    }
                    else -> onStatus("Ignoring unsupported control message 0x${frame.messageType.toString(16)} from receiverId=${peer.receiverId}.")
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) reason = "control channel failed: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            removeReceiver(peer, reason)
        }
    }

    private fun removeReceiver(peer: HostReceiverPeer, reason: String) {
        val removed = synchronized(groupStartLock) {
            if (!receivers.remove(peer.receiverId, peer)) {
                false
            } else {
                if (receivers.isEmpty() && !groupStartScheduled.get()) {
                    firstReceiverAdmissionNs = 0L
                }
                true
            }
        }
        if (!removed) return
        peer.close()
        if (!closed.get()) {
            onStatus("Speaker disconnected · receiverId=${peer.receiverId} · $reason · ${receivers.size}/${PreviewConfig.MAX_SPEAKERS} connected.")
        }

        if (!closed.get() && !groupStartScheduled.get() && receivers.isNotEmpty()) {
            Thread(::tryScheduleGroupStart, "syncinit-group-start-recheck").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Starts the current READY cohort on one shared future master timestamp and one sample index.
     * Each receiver has at least 3 seconds of contiguous PCM before READY; selecting a point
     * 2 seconds behind the producer cursor keeps that sample inside every READY receiver's buffer.
     */
    private fun scheduleGroupReadinessTimeout(admissionStartedAtNs: Long) {
        Thread({
            val deadlineNs = admissionStartedAtNs + PreviewConfig.GROUP_READINESS_TIMEOUT_NS
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs > 0L) {
                try {
                    Thread.sleep(
                        remainingNs / 1_000_000L,
                        (remainingNs % 1_000_000L).toInt()
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }

            synchronized(groupStartLock) {
                if (closed.get() || groupStartScheduled.get() ||
                    firstReceiverAdmissionNs != admissionStartedAtNs) return@synchronized

                val notReady = receivers.values.filter { peer ->
                    runCatching {
                        peer.session.state() != com.syncinit.app.core.ReceiverSessionState.Ready
                    }.getOrDefault(true)
                }
                if (notReady.isNotEmpty()) {
                    onStatus(
                        "GROUP_START_READINESS_TIMEOUT elapsedMs=${PreviewConfig.GROUP_READINESS_TIMEOUT_NS / 1_000_000L} " +
                            "excludingReceivers=${notReady.joinToString(",") { it.receiverId.toString() }}"
                    )
                    notReady.forEach { peer ->
                        removeReceiver(peer, "did not reach READY before the group startup deadline")
                    }
                }
                if (receivers.isNotEmpty()) tryScheduleGroupStart()
            }
        }, "syncinit-group-readiness-timeout").apply {
            isDaemon = true
            start()
        }
    }

    private fun tryScheduleGroupStart() {
        synchronized(groupStartLock) {
            if (closed.get() || groupStartScheduled.get()) return
            val cohort = receivers.values.sortedBy { it.receiverId }
            if (cohort.isEmpty()) return
            val allReady = cohort.all { peer ->
                runCatching { peer.session.state() == com.syncinit.app.core.ReceiverSessionState.Ready }
                    .getOrDefault(false)
            }
            if (!allReady) return

            val nowNs = System.nanoTime()
            val admissionStartedAt = firstReceiverAdmissionNs
            if (admissionStartedAt == 0L) return
            val admissionDeadlineNs = admissionStartedAt + PreviewConfig.GROUP_ADMISSION_WINDOW_NS
            if (nowNs < admissionDeadlineNs) {
                if (groupStartCheckQueued.compareAndSet(false, true)) {
                    Thread({
                        try {
                            val remainingNs = admissionDeadlineNs - System.nanoTime()
                            if (remainingNs > 0L) {
                                Thread.sleep(
                                    remainingNs / 1_000_000L,
                                    (remainingNs % 1_000_000L).toInt()
                                )
                            }
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        } finally {
                            groupStartCheckQueued.set(false)
                        }
                        tryScheduleGroupStart()
                    }, "syncinit-group-start-window").apply {
                        isDaemon = true
                        start()
                    }
                }
                onStatus("Group playback will arm when the speaker admission window closes and all admitted speakers are READY.")
                return
            }

            val cursor = streamCursor.get()
            if (cursor == null) {
                onStatus("Group start is waiting for the shared audio timeline.")
                return
            }

            val targetMasterTimeNs = nowNs + PreviewConfig.GROUP_START_LEAD_NS
            val bufferedOffsetFrames = PreviewConfig.SAMPLE_RATE_HZ.toLong() * PreviewConfig.GROUP_START_BUFFER_MS / 1_000L
            val latestAlignedSample = cursor.nextSampleIndex -
                (cursor.nextSampleIndex % PreviewConfig.CHUNK_FRAMES.toLong())
            val startSampleIndex = latestAlignedSample - bufferedOffsetFrames
            if (startSampleIndex < 0L) {
                onStatus("Group start is waiting for enough shared PCM history.")
                return
            }

            val anchorFrame = NativeProtocol.encodeAnchorFrame(
                kind = 0,
                masterTimeNs = targetMasterTimeNs,
                sampleIndex = startSampleIndex,
                playing = true
            )

            val armed = mutableListOf<HostReceiverPeer>()
            cohort.forEach { peer ->
                val result = runCatching { peer.session.arm() }
                    .getOrDefault(ReceiverSessionResult.Closed)
                if (result == ReceiverSessionResult.Accepted) {
                    armed += peer
                } else {
                    removeReceiver(peer, "could not arm coordinated playback: $result")
                }
            }
            if (armed.isEmpty()) return

            val started = mutableListOf<HostReceiverPeer>()
            armed.forEach { peer ->
                val result = runCatching {
                    peer.session.onAnchor(
                        kind = 0,
                        masterTimeNs = targetMasterTimeNs,
                        sampleIndex = startSampleIndex,
                        playing = true
                    )
                }.getOrDefault(ReceiverSessionResult.Closed)
                if (result == ReceiverSessionResult.Accepted) {
                    started += peer
                } else {
                    removeReceiver(peer, "could not accept coordinated start ANCHOR: $result")
                }
            }
            if (started.isEmpty()) return

            groupStartScheduled.set(true)
            started.forEach { peer ->
                try {
                    peer.control.writeEncodedFrame(anchorFrame)
                } catch (error: Exception) {
                    removeReceiver(
                        peer,
                        "could not deliver group-start ANCHOR: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
            onStatus(
                "GROUP_START_SCHEDULED masterTimeNs=$targetMasterTimeNs sampleIndex=$startSampleIndex " +
                    "receivers=${started.size}. New speakers must join a restarted host session."
            )
        }
    }

    /** One producer generates a single sample-indexed stream; each speaker gets the same frames. */
    private fun startAudioTestStream(sessionId: Long) {
        if (audioSenderThread?.isAlive == true) return
        val sender = Thread({
            var sequence = 0L
            var sampleIndex = 0L
            var nextDeadlineNs = System.nanoTime()
            streamCursor.set(StreamCursor(sampleIndex, nextDeadlineNs))

            try {
                onStatus("Shared 1 kHz timing-click source ready · fan-out limit ${PreviewConfig.MAX_SPEAKERS} speakers.")
                while (!closed.get()) {
                    val peers = receivers.values.toList()
                    if (peers.isEmpty()) {
                        nextDeadlineNs = System.nanoTime()
                        streamCursor.set(StreamCursor(sampleIndex, nextDeadlineNs))
                        Thread.sleep(50)
                        continue
                    }

                    val pcm = ByteArray(PreviewConfig.CHUNK_FRAMES * PreviewConfig.CHANNELS * 2)
                    for (frameIndex in 0 until PreviewConfig.CHUNK_FRAMES) {
                        val absoluteSample = sampleIndex + frameIndex
                        val positionInSecond = absoluteSample % PreviewConfig.SAMPLE_RATE_HZ.toLong()
                        val sample = if (positionInSecond < 1_440L) {
                            (sin(2.0 * PI * 1_000.0 * positionInSecond / PreviewConfig.SAMPLE_RATE_HZ) * 6_000.0).toInt()
                        } else {
                            0
                        }
                        for (channel in 0 until PreviewConfig.CHANNELS) {
                            val offset = (frameIndex * PreviewConfig.CHANNELS + channel) * 2
                            pcm[offset] = (sample and 0xff).toByte()
                            pcm[offset + 1] = ((sample shr 8) and 0xff).toByte()
                        }
                    }

                    val encoded = NativePcmJitterBuffer.encodeChunk(
                        sessionId = sessionId,
                        sequence = sequence,
                        sampleIndex = sampleIndex,
                        frames = PreviewConfig.CHUNK_FRAMES,
                        channels = PreviewConfig.CHANNELS,
                        pcm16LittleEndian = pcm
                    )
                    peers.forEach { peer ->
                        if (!peer.offerAudio(encoded)) {
                            onStatus("DIAG HOST_AUDIO_QUEUE_OVERFLOW receiverId=${peer.receiverId} limitChunks=${PreviewConfig.AUDIO_QUEUE_CHUNKS} limitMs=${PreviewConfig.AUDIO_QUEUE_CHUNKS * PreviewConfig.CHUNK_PERIOD_NS / 1_000_000L}")
                            removeReceiver(peer, "audio send queue exceeded ${PreviewConfig.AUDIO_QUEUE_CHUNKS * PreviewConfig.CHUNK_PERIOD_NS / 1_000_000L} ms of backlog")
                        }
                    }

                    sequence = (sequence + 1L) and 0xffff_ffffL
                    sampleIndex += PreviewConfig.CHUNK_FRAMES
                    nextDeadlineNs += PreviewConfig.CHUNK_PERIOD_NS
                    streamCursor.set(StreamCursor(sampleIndex, nextDeadlineNs))
                    val remainingNs = nextDeadlineNs - System.nanoTime()
                    if (remainingNs > 0L) {
                        Thread.sleep(
                            remainingNs / 1_000_000L,
                            (remainingNs % 1_000_000L).toInt()
                        )
                    } else {
                        nextDeadlineNs = System.nanoTime()
                    }
                }
            } catch (error: InterruptedException) {
                if (!closed.get()) Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (!closed.get()) {
                    onStatus("Shared PCM16 test stream stopped: ${error.message ?: error.javaClass.simpleName}")
                    close()
                }
            }
        }, "syncinit-master-audio-source").apply { isDaemon = true }
        audioSenderThread = sender
        sender.start()
    }

    private fun startTimeSyncServer() {
        check(timeSyncThread == null) { "UDP time-sync server is already running" }
        val worker = Thread(::runTimeSyncServer, "syncinit-master-time-sync").apply {
            isDaemon = true
        }
        timeSyncThread = worker
        worker.start()
    }

    /** One UDP listener dispatches each packet to the native receiver session named in it. */
    private fun runTimeSyncServer() {
        val server = checkNotNull(timeSyncServer) { "UDP time-sync listener was not started" }
        onStatus("DIAG HOST_UDP_SOCKET_READY bind=0.0.0.0:${server.localPort} bound=true multiReceiver=true")

        var responseCount = 0
        var malformedUdpCount = 0
        var rejectedUdpAddressCount = 0
        var rejectedUdpPortCount = 0
        var rejectedNativePacketCount = 0
        try {
            while (!closed.get()) {
                val datagram = try {
                    server.receive()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (error: FrameProtocolException) {
                    malformedUdpCount += 1
                    if (malformedUdpCount == 1 || malformedUdpCount % 10 == 0) {
                        onStatus("DIAG HOST_UDP_MALFORMED_DATAGRAM count=$malformedUdpCount ${NetworkDiagnostics.exceptionDetails(error)}")
                    }
                    continue
                } catch (error: SocketException) {
                    if (closed.get()) break
                    throw error
                }

                val candidates = receivers.values.filter { it.control.remoteAddress == datagram.source.address }
                if (candidates.isEmpty()) {
                    rejectedUdpAddressCount += 1
                    if (rejectedUdpAddressCount == 1 || rejectedUdpAddressCount % 10 == 0) {
                        onStatus("DIAG HOST_UDP_SOURCE_IP_REJECTED count=$rejectedUdpAddressCount source=${datagram.source} reason=no admitted control peer has this address")
                    }
                    continue
                }

                val t3Ns = System.nanoTime()
                var matchedPeer: HostReceiverPeer? = null
                var response: ByteArray? = null
                var newlyPinnedPeer: HostReceiverPeer? = null
                for (peer in candidates) {
                    val pinned = peer.udpPeer.get()
                    if (pinned != null && pinned != datagram.source) {
                        rejectedUdpPortCount += 1
                        continue
                    }

                    val candidateResponse = try {
                        peer.session.timeSyncResponse(datagram.bytes, datagram.receivedAtNs, t3Ns)
                    } catch (_: IllegalStateException) {
                        // The Speaker may have disconnected after the receiver snapshot was taken.
                        null
                    } ?: continue

                    if (pinned == null) {
                        if (peer.udpPeer.compareAndSet(null, datagram.source)) {
                            newlyPinnedPeer = peer
                        } else if (peer.udpPeer.get() != datagram.source) {
                            rejectedUdpPortCount += 1
                            continue
                        }
                    }
                    matchedPeer = peer
                    response = candidateResponse
                    break
                }

                val target = matchedPeer
                val encodedResponse = response
                if (target == null || encodedResponse == null) {
                    rejectedNativePacketCount += 1
                    if (rejectedNativePacketCount == 1 || rejectedNativePacketCount % 10 == 0) {
                        onStatus("DIAG HOST_UDP_NATIVE_PACKET_REJECTED count=$rejectedNativePacketCount source=${datagram.source} bytes=${datagram.bytes.size} reason=no active receiver session accepted packet")
                    }
                    continue
                }

                if (newlyPinnedPeer === target) {
                    onStatus("DIAG HOST_UDP_PEER_PINNED receiverId=${target.receiverId} source=${datagram.source}")
                }
                val responseStartedAt = System.nanoTime()
                server.sendTo(encodedResponse, datagram.source)
                val sendElapsedUs = (System.nanoTime() - responseStartedAt) / 1_000L

                responseCount += 1
                if (responseCount == 1 || responseCount % 20 == 0) {
                    onStatus("DIAG HOST_UDP_RESPONSE_SENT count=$responseCount receiverId=${target.receiverId} destination=${datagram.source} bytes=${encodedResponse.size} sendElapsedUs=$sendElapsedUs")
                    onStatus("UDP time sync active · $responseCount validated requests answered across ${receivers.size} speaker(s).")
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                onStatus("UDP time-sync server failed: ${error.message ?: error.javaClass.simpleName}")
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
        val activePeers = receivers.values.toList()
        receivers.clear()
        activePeers.forEach { it.close() }

        audioSenderThread?.interrupt()
        audioSenderThread = null
        timeSyncThread?.interrupt()
        timeSyncThread = null

        closeQuietly(controlServer)
        closeQuietly(audioServer)
        closeQuietly(timeSyncServer)
        closeQuietly(advertiser)
        controlServer = null
        audioServer = null
        timeSyncServer = null
        advertiser = null
        nativeSessionConfig?.token?.fill(0)
        nativeSessionConfig = null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xff) }

    private fun closeQuietly(resource: Closeable?) {
        try { resource?.close() } catch (_: Exception) { }
    }
}
