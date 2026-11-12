// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.graphics.Color
import android.graphics.Typeface
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.syncinit.app.diagnostics.DiagnosticLogStore
import com.syncinit.app.audio.SystemAudioCaptureBuffer
import com.syncinit.app.audio.SystemAudioCaptureService
import com.syncinit.backend.BackendApiClient
import com.syncinit.backend.BackendConnectionState
import com.syncinit.backend.BackendDeviceState
import com.syncinit.backend.BackendEvent
import com.syncinit.backend.BackendSessionCoordinator
import com.syncinit.backend.BackendSessionCoordinatorListener
import com.syncinit.backend.BackendSessionRole
import com.syncinit.app.network.CloudHostSession
import com.syncinit.app.network.CloudSpeakerRoom
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var foundationStatus: TextView
    private lateinit var hostDetails: TextView
    private lateinit var hostJoinCodeDetails: TextView
    private lateinit var hostQrImage: ImageView
    private lateinit var synchronizedPlaybackButton: Button
    private lateinit var audioSelectionDetails: TextView
    private lateinit var inviteStatus: TextView
    private lateinit var debugLog: TextView
    private lateinit var diagnosticLogStore: DiagnosticLogStore
    private val diagnosticEvents = ArrayDeque<String>()

    companion object {
        private const val REQUEST_EXPORT_DIAGNOSTICS = 4102
        private const val REQUEST_PICK_AUDIO = 4103
        private const val REQUEST_CAPTURE_SYSTEM_AUDIO = 4104
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 4105
        private const val BACKEND_BASE_URL = "https://sync-in-it.onrender.com"
    }

    private data class HostBackendSession(
        val api: BackendApiClient,
        val sessionId: String,
        val joinCode: String,
        val hostToken: String,
        val coordinator: BackendSessionCoordinator,
        val media: CloudHostSession
    )

    private data class SpeakerBackendSession(
        val api: BackendApiClient,
        val sessionId: String,
        val deviceToken: String,
        val coordinator: BackendSessionCoordinator,
        val room: CloudSpeakerRoom
    )

    @Volatile private var hostBackendSession: HostBackendSession? = null
    @Volatile private var speakerBackendSession: SpeakerBackendSession? = null
    @Volatile private var selectedAudioUri: Uri? = null
    @Volatile private var selectedAudioName: String? = null
    @Volatile private var selectedSystemAudio = false
    private val sessionGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val synchronizedPlaybackStartInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnosticLogStore = DiagnosticLogStore(applicationContext)
        SystemAudioCaptureBuffer.statusListener = { message ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    appendDiagnostic("SYSTEM_AUDIO", message)
                    if (message.startsWith("SYSTEM_AUDIO_LEVEL")) {
                        return@runOnUiThread
                    }
                    postStatus(message)
                    if (message.contains("failed", ignoreCase = true) ||
                        message.contains("stopped", ignoreCase = true)
                    ) {
                        val wasSystemAudioSelected = selectedSystemAudio
                        selectedSystemAudio = false
                        if (wasSystemAudioSelected && selectedAudioUri == null &&
                            selectedAudioName == null && ::audioSelectionDetails.isInitialized
                        ) {
                            audioSelectionDetails.text =
                                "System audio capture stopped or unavailable · select Capture system audio again."
                        }
                    } else if (
                        message.contains("capture service is active", ignoreCase = true) ||
                        message.contains("playback capture started", ignoreCase = true)
                    ) {
                        if (::audioSelectionDetails.isInitialized) {
                            audioSelectionDetails.text =
                                "System audio selected · capture running. Play audio in an eligible app before starting the Host."
                        }
                    }
                }
            }
        }
        window.statusBarColor = Color.rgb(6, 9, 19)
        window.navigationBarColor = Color.rgb(6, 9, 19)
        window.decorView.systemUiVisibility = 0

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(6, 9, 19))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(32))
        }
        scroll.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        val brandCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = panelBackground()
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.sync_in_it)
            contentDescription = "Sync In It logo"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        brandCard.addView(logo, LinearLayout.LayoutParams(dp(54), dp(54)))
        val brandCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, 0, 0)
        }
        brandCopy.addView(TextView(this).apply {
            text = "Sync In It"
            textSize = 22f
            setTextColor(Color.rgb(244, 247, 255))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.025f
        })
        brandCopy.addView(TextView(this).apply {
            text = "CLOUD AUDIO · LIVEKIT WEBRTC"
            textSize = 9f
            setTextColor(Color.rgb(126, 148, 183))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.11f
            setPadding(0, dp(4), 0, 0)
        })
        brandCard.addView(brandCopy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        brandCard.addView(TextView(this).apply {
            text = "PREVIEW"
            textSize = 9f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(Color.rgb(104, 233, 246))
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = pillBackground(Color.rgb(13, 47, 62), Color.rgb(32, 198, 220))
        })
        content.addView(brandCard, sectionMargins())

        TextView(this).apply {
            text = "One source.\nEvery phone connected."
            textSize = 27f
            setTextColor(Color.rgb(241, 245, 255))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.035f
            setPadding(dp(2), dp(23), dp(2), dp(7))
        }.also { content.addView(it) }

        TextView(this).apply {
            text = "Create a cloud speaker group. Host and Speakers can be on completely different networks."
            textSize = 14f
            setTextColor(Color.rgb(157, 169, 194))
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(2), 0, dp(2), dp(14))
        }.also { content.addView(it) }

        val featureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        featureRow.addView(featureChip("CLOUD CONNECTED"), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) })
        featureRow.addView(featureChip("UP TO ${PreviewConfig.MAX_SPEAKERS} SPEAKERS"))
        content.addView(featureRow, sectionMargins(bottom = 18))

        foundationStatus = TextView(this).apply {
            text = "Cloud transport stack initialized · backend connectivity is verified when a session starts."
            textSize = 12f
            setTextColor(Color.rgb(169, 185, 211))
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = panelBackground(Color.rgb(13, 20, 34))
        }
        content.addView(foundationStatus, sectionMargins(bottom = 20))

        val hostCard = sectionCard(
            parent = content,
            eyebrow = "01 / HOST",
            title = "Start a speaker group",
            description = "Choose audio from this phone and publish it to the cloud. Every Speaker receives the same LiveKit room audio over the internet."
        )
        hostCard.addView(actionButton("Choose audio file") { chooseAudioFile() }, sectionMargins(top = 12, bottom = 7))
        hostCard.addView(actionButton("Capture system audio") { requestSystemAudioCapture() }, sectionMargins(bottom = 7))
        audioSelectionDetails = TextView(this).apply {
            text = "No file selected · diagnostic timing signal will be used."
            textSize = 12f
            setTextColor(Color.rgb(159, 174, 198))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = insetBackground()
        }
        hostCard.addView(audioSelectionDetails, sectionMargins(bottom = 7))
        hostCard.addView(actionButton("Use diagnostic test signal") { clearAudioSelection() }, sectionMargins(bottom = 8))
        hostCard.addView(actionButton("Start hosting") { startHosting() }, sectionMargins(bottom = 8))
        synchronizedPlaybackButton = actionButton("Start synchronized playback") {
            startSynchronizedPlayback()
        }.apply { isEnabled = false }
        hostCard.addView(synchronizedPlaybackButton, sectionMargins(bottom = 8))
        hostDetails = TextView(this).apply {
            text = "You are not hosting a session."
            textSize = 13f
            setTextColor(Color.rgb(159, 174, 198))
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = insetBackground()
        }
        hostCard.addView(hostDetails)
        hostJoinCodeDetails = TextView(this).apply {
            text = "A QR invitation will appear when the backend session is ready."
            textSize = 12f
            setTextColor(Color.rgb(159, 174, 198))
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }
        hostCard.addView(hostJoinCodeDetails)
        hostQrImage = ImageView(this).apply {
            contentDescription = "Sync In It speaker invitation QR code"
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = ImageView.GONE
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        hostCard.addView(hostQrImage, LinearLayout.LayoutParams(dp(248), dp(248)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(4)
            bottomMargin = dp(6)
        })

        val joinCard = sectionCard(
            parent = content,
            eyebrow = "02 / JOIN",
            title = "Join a cloud speaker group",
            description = "Scan the Host invitation. Backend authentication and LiveKit WebRTC deliver audio over the internet. Same Wi-Fi is not required."
        )
        joinCard.addView(actionButton("Scan Host QR code") { scanHostQrCode() }, sectionMargins(top = 12, bottom = 7))
        inviteStatus = TextView(this).apply {
            text = "Ready to scan a Host invitation."
            textSize = 12f
            setTextColor(Color.rgb(142, 160, 190))
            setPadding(dp(2), dp(7), dp(2), dp(10))
        }
        joinCard.addView(inviteStatus)
        val connectionCard = sectionCard(
            parent = content,
            eyebrow = "03 / SESSION",
            title = "Connection status",
            description = "Live backend session, WebRTC media and Speaker status appear here."
        )
        status = TextView(this).apply {
            text = "Idle · connect to the internet to host or join a cloud speaker group."
            textSize = 13f
            setTextColor(Color.rgb(190, 204, 228))
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = insetBackground()
        }
        connectionCard.addView(status, sectionMargins(top = 10, bottom = 10))
        connectionCard.addView(actionButton("Stop session / disconnect") { stopConnections() })

        val diagnosticsCard = sectionCard(
            parent = content,
            eyebrow = "04 / DIAGNOSTICS",
            title = "Cloud media debugger",
            description = "Capture backend, LiveKit and audio events. Save the full app-private diagnostic history as a plain-text report."
        )
        val diagnosticActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        diagnosticActions.addView(actionButton("Save diagnostic report (.txt)") { exportDiagnosticLog() }, sectionMargins(top = 7))
        diagnosticActions.addView(actionButton("Clear diagnostic log") { clearDiagnosticLog() }, sectionMargins(top = 7))
        diagnosticsCard.addView(diagnosticActions, sectionMargins(top = 12, bottom = 10))
        debugLog = TextView(this).apply {
            text = "No diagnostic events yet."
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(144, 165, 194))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            maxHeight = dp(220)
            isVerticalScrollBarEnabled = true
            background = insetBackground()
        }
        diagnosticsCard.addView(debugLog)

        TextView(this).apply {
            text = "CLOUD PLAYBACK NOTE\nThe Host prepares the selected local file or audio source, waits for connected Speakers to attach their PCM output and estimate the shared clock, then schedules a common future playback start. LiveKit remains the cloud transport. Each Speaker mutes default WebRTC playout and writes decoded PCM through a locally scheduled Android AudioTrack with buffer-based clock correction. Physical output latency still varies by device and requires external acoustic measurement; this preview is an engineering synchronization test, not a certified acoustic-latency guarantee. Android may block capture for protected or capture-restricted apps."
            textSize = 11f
            setTextColor(Color.rgb(119, 137, 168))
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(3), dp(18), dp(3), 0)
        }.also { content.addView(it) }

        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
    }


    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_AUDIO) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            stopHostForSourceChange("local-file")
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            stopSystemAudioCapture("local-file-selected")
            selectedAudioUri = uri
            selectedAudioName = queryDisplayName(uri)
            audioSelectionDetails.text =
                "Selected: ${selectedAudioName}\nThis file will be decoded and streamed to connected Speakers."
            appendDiagnostic("AUDIO_SOURCE_SELECTED", "name=${selectedAudioName} uri=$uri")
            return
        }
        if (requestCode == REQUEST_CAPTURE_SYSTEM_AUDIO) {
            if (resultCode != RESULT_OK || data == null) {
                appendDiagnostic("SYSTEM_AUDIO_CONSENT_DENIED", "MediaProjection permission was cancelled.")
                postStatus("System-audio capture permission was not granted.")
                return
            }
            val serviceIntent = Intent(this, SystemAudioCaptureService::class.java).apply {
                putExtra(SystemAudioCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(SystemAudioCaptureService.EXTRA_RESULT_DATA, data)
            }
            try {
                stopHostForSourceChange("system-audio")
                startForegroundService(serviceIntent)
                selectedSystemAudio = true
                selectedAudioUri = null
                selectedAudioName = null
                audioSelectionDetails.text = "System audio capture selected · waiting for Android playback capture to start."
                appendDiagnostic("SYSTEM_AUDIO_CONSENT_GRANTED", "MediaProjection consent granted; foreground capture service requested.")
            } catch (error: Exception) {
                selectedSystemAudio = false
                appendDiagnostic("SYSTEM_AUDIO_START_FAILED", error.message ?: error.javaClass.simpleName)
                postStatus("Could not start system-audio capture: ${error.message ?: error.javaClass.simpleName}")
            }
            return
        }
        if (requestCode != REQUEST_EXPORT_DIAGNOSTICS || resultCode != RESULT_OK) return
        val destination = data?.data ?: return
        Thread({
            try {
                val report = diagnosticLogStore.snapshot()
                contentResolver.openOutputStream(destination, "w")?.use { output ->
                    output.write(report.toByteArray(Charsets.UTF_8))
                    output.flush()
                } ?: throw IllegalStateException("Could not open the selected diagnostic file.")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "Diagnostic report saved as a .txt file.", Toast.LENGTH_LONG).show()
                        appendDiagnostic("EXPORT", "Diagnostic report saved through Android document picker.")
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(
                            this,
                            "Could not save diagnostic report: ${error.message ?: error.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                        appendDiagnostic("EXPORT_FAILED", error.message ?: error.javaClass.simpleName)
                    }
                }
            }
        }, "syncinit-diagnostic-export").apply { isDaemon = true }.start()
    }

    @Deprecated("Deprecated in Android API")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            launchSystemAudioConsent()
        } else {
            postStatus("Android RECORD_AUDIO permission is required for playback capture.")
            appendDiagnostic("SYSTEM_AUDIO_PERMISSION_DENIED", "RECORD_AUDIO runtime permission denied.")
        }
    }

    private fun requestSystemAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            postStatus("System-audio capture requires Android 10 or newer. Use a local audio file on this device.")
            return
        }
        if (SystemAudioCaptureBuffer.isActive()) {
            postStatus("System-audio capture is already active. Start hosting when the source app is playing audio.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
            return
        }
        launchSystemAudioConsent()
    }

    private fun launchSystemAudioConsent() {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE_SYSTEM_AUDIO)
        } catch (error: Exception) {
            postStatus("Could not open Android capture consent: ${error.message ?: error.javaClass.simpleName}")
            appendDiagnostic("SYSTEM_AUDIO_CONSENT_FAILED", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun stopSystemAudioCapture(reason: String) {
        runCatching { stopService(Intent(this, SystemAudioCaptureService::class.java)) }
        SystemAudioCaptureBuffer.stop(reason)
        selectedSystemAudio = false
    }

    private fun chooseAudioFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_AUDIO)
        } catch (error: Exception) {
            Toast.makeText(this, "No compatible audio file picker is available.", Toast.LENGTH_LONG).show()
            appendDiagnostic("AUDIO_PICKER_FAILED", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun clearAudioSelection() {
        stopHostForSourceChange("diagnostic-test-tone")
        stopSystemAudioCapture("diagnostic-source-selected")
        selectedAudioUri = null
        selectedAudioName = null
        audioSelectionDetails.text = "No file selected · diagnostic timing signal will be used."
        appendDiagnostic("AUDIO_SOURCE_SELECTED", "source=diagnostic-test-tone")
    }

    private fun queryDisplayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Selected audio file"
    }

    private fun startHosting() {
        if (selectedSystemAudio && !SystemAudioCaptureBuffer.isActive()) {
            postStatus("System-audio capture is starting or inactive. Wait for the capture-active status, then retry.")
            return
        }
        stopConnections(stopCapture = false)
        val attempt = sessionGeneration.get()
        val audioUri = selectedAudioUri
        val audioName = selectedAudioName
        val useSystemAudio = selectedSystemAudio
        hostDetails.text = "Creating a cloud session…"
        hostJoinCodeDetails.text = "Connecting Host media to LiveKit before showing the invitation."
        hostQrImage.visibility = ImageView.GONE
        postStatus("Creating a backend session and connecting the cloud media publisher…")

        Thread({
            var api: BackendApiClient? = null
            var createdSession: com.syncinit.backend.BackendSessionCreated? = null
            var state: HostBackendSession? = null
            try {
                val backendApi = BackendApiClient(BACKEND_BASE_URL)
                api = backendApi
                val created = backendApi.createSession(
                    hostName = Build.MODEL.trim().take(48).ifBlank { "Android Host" },
                    maxSpeakers = PreviewConfig.MAX_SPEAKERS
                )
                createdSession = created
                check(sessionGeneration.get() == attempt) { "Host start was cancelled" }

                val coordinator = createBackendCoordinator(
                    api = backendApi,
                    sessionId = created.sessionId,
                    token = created.hostToken,
                    role = BackendSessionRole.HOST,
                    label = "Host"
                )
                val media = CloudHostSession(
                    context = applicationContext,
                    api = backendApi,
                    sessionId = created.sessionId,
                    hostToken = created.hostToken,
                    onStatus = ::postStatus,
                    audioFileUri = audioUri,
                    audioFileLabel = audioName,
                    systemAudioCaptureEnabled = useSystemAudio
                )
                state = HostBackendSession(
                    api = backendApi,
                    sessionId = created.sessionId,
                    joinCode = created.joinCode,
                    hostToken = created.hostToken,
                    coordinator = coordinator,
                    media = media
                )
                hostBackendSession = state

                val qrPayload = BackendInviteCodec.encode(
                    BackendInvite(created.sessionId, created.joinCode)
                )
                val qrBitmap = BackendInviteQr.createBitmap(qrPayload)
                val sourceDescription = when {
                    useSystemAudio -> "Android app playback capture"
                    audioUri != null -> "Local file: ${audioName ?: "selected audio"}"
                    else -> "Diagnostic 1 kHz test signal"
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed || hostBackendSession !== state) return@runOnUiThread
                    hostDetails.text = "Cloud Host created · connecting media source…"
                    hostJoinCodeDetails.text =
                        "Backend session: ${created.sessionId}\nJoin code: ${created.joinCode}\nScan this QR on each Speaker. Same Wi-Fi is not required."
                    hostQrImage.setImageBitmap(qrBitmap)
                    hostQrImage.visibility = ImageView.VISIBLE
                    inviteStatus.text = "Invitation ready · Host media is connecting."
                }

                coordinator.start()
                media.startAndAwaitReady()
                check(sessionGeneration.get() == attempt && hostBackendSession === state) {
                    "Host start was cancelled"
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed || hostBackendSession !== state) return@runOnUiThread
                    hostDetails.text =
                        "Cloud Host active · $sourceDescription\nJoin every Speaker, then start synchronized playback."
                    inviteStatus.text = "Host is online in LiveKit. Share the QR invitation."
                    synchronizedPlaybackButton.isEnabled = true
                    postStatus("Host cloud media is connected. Waiting for Speakers to become ready.")
                }
            } catch (error: Exception) {
                state?.let(::closeBackendHostState)
                if (state != null && hostBackendSession === state) hostBackendSession = null
                if (state == null && createdSession != null && api != null) {
                    runCatching { api.closeSession(createdSession.sessionId, createdSession.hostToken) }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed || sessionGeneration.get() != attempt) return@runOnUiThread
                    synchronizedPlaybackButton.isEnabled = false
                    hostDetails.text = "Cloud Host did not start."
                    hostJoinCodeDetails.text =
                        "Cloud setup failed: ${error.message ?: error.javaClass.simpleName}"
                    hostQrImage.setImageDrawable(null)
                    hostQrImage.visibility = ImageView.GONE
                    postStatus("Could not start cloud Host: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }, "syncinit-cloud-host-startup").apply { isDaemon = true }.start()
    }

    private fun scanHostQrCode() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        try {
            GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener { barcode ->
                    val invite = try {
                        BackendInviteCodec.decode(barcode.rawValue.orEmpty())
                    } catch (error: Exception) {
                        postStatus("Invalid invitation QR: ${error.message ?: "unsupported code"}")
                        return@addOnSuccessListener
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        inviteStatus.text = "Invitation scanned · authenticating with the backend…"
                        postStatus("QR accepted. Joining the backend session and connecting to LiveKit…")
                        joinCloudSession(invite)
                    }
                }
                .addOnCanceledListener {
                    postStatus("QR scanning cancelled.")
                }
                .addOnFailureListener { error ->
                    postStatus(
                        "QR scanner unavailable: ${error.message ?: error.javaClass.simpleName}. " +
                            "Check Google Play services and try again."
                    )
                    appendDiagnostic("QR_SCAN_FAILED", error.message ?: error.javaClass.simpleName)
                }
        } catch (error: Exception) {
            postStatus("Could not start QR scanner: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun joinCloudSession(invite: BackendInvite) {
        stopConnections()
        val attempt = sessionGeneration.get()
        inviteStatus.text = "Joining cloud session…"
        Thread({
            var api: BackendApiClient? = null
            var joinedSessionId: String? = null
            var joinedDeviceToken: String? = null
            var coordinator: BackendSessionCoordinator? = null
            var room: CloudSpeakerRoom? = null
            try {
                val backendApi = BackendApiClient(BACKEND_BASE_URL)
                api = backendApi
                val joined = backendApi.joinSession(
                    joinCode = invite.joinCode,
                    deviceName = Build.MODEL.trim().take(48).ifBlank { "Android Speaker" }
                )
                joinedSessionId = joined.sessionId
                joinedDeviceToken = joined.deviceToken
                check(joined.sessionId == invite.sessionId) {
                    "The scanned invitation does not match the backend session"
                }
                check(sessionGeneration.get() == attempt) { "Speaker join was cancelled" }

                val credentials = backendApi.createLiveKitToken(
                    sessionId = joined.sessionId,
                    bearerToken = joined.deviceToken
                )
                check(credentials.role == "speaker") { "Backend returned a non-Speaker LiveKit token" }
                check(
                    credentials.sampleRateHz == PreviewConfig.SAMPLE_RATE_HZ &&
                        credentials.channels == PreviewConfig.CHANNELS &&
                        credentials.chunkFrames == PreviewConfig.CHUNK_FRAMES
                ) { "Cloud audio format does not match this Android build" }

                val activeCoordinator = createBackendCoordinator(
                    api = backendApi,
                    sessionId = joined.sessionId,
                    token = joined.deviceToken,
                    role = BackendSessionRole.SPEAKER,
                    label = "Speaker"
                )
                coordinator = activeCoordinator
                postStatus("Estimating the shared playback clock…")
                val initialClockOffsetNs = backendApi.estimateServerClockOffsetNs(
                    joined.sessionId,
                    joined.deviceToken,
                    sampleCount = 9
                )
                appendDiagnostic("PLAYBACK_CLOCK_ESTIMATE", "serverMinusLocalNs=$initialClockOffsetNs")
                val activeRoom = CloudSpeakerRoom(
                    context = applicationContext,
                    serverUrl = credentials.serverUrl,
                    participantToken = credentials.participantToken,
                    initialServerMinusLocalClockOffsetNs = initialClockOffsetNs,
                    refreshServerMinusLocalClockOffsetNs = {
                        backendApi.estimateServerClockOffsetNs(
                            joined.sessionId,
                            joined.deviceToken,
                            sampleCount = 5
                        )
                    },
                    onStatus = ::postStatus,
                    onPlaybackStarted = {
                        activeCoordinator.updateSpeakerState(
                            BackendDeviceState.PLAYING,
                            "scheduled PCM playback started"
                        )
                    },
                    onPlaybackDegraded = { detail ->
                        activeCoordinator.updateSpeakerState(
                            BackendDeviceState.DEGRADED,
                            detail
                        )
                    }
                )
                room = activeRoom
                check(sessionGeneration.get() == attempt && !isFinishing && !isDestroyed) {
                    "Speaker join was cancelled"
                }

                val state = SpeakerBackendSession(
                    api = backendApi,
                    sessionId = joined.sessionId,
                    deviceToken = joined.deviceToken,
                    coordinator = activeCoordinator,
                    room = activeRoom
                )
                speakerBackendSession = state
                activeCoordinator.start()
                runCatching {
                    backendApi.updateDeviceState(
                        joined.sessionId,
                        joined.deviceToken,
                        BackendDeviceState.SYNCING,
                        "Connecting to LiveKit cloud media"
                    )
                }
                activeRoom.connectAndAwait()
                val coordinatorDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (activeCoordinator.currentState() != BackendConnectionState.CONNECTED) {
                    check(activeCoordinator.currentState() != BackendConnectionState.FAILED) {
                        "Speaker backend event channel failed"
                    }
                    check(System.nanoTime() < coordinatorDeadlineNs) {
                        "Speaker backend event channel did not become ready"
                    }
                    Thread.sleep(100)
                }
                backendApi.updateDeviceState(
                    joined.sessionId,
                    joined.deviceToken,
                    BackendDeviceState.READY,
                    "LiveKit PCM sink attached and shared clock synchronized"
                )
                appendDiagnostic(
                    "LIVEKIT_AUDIO_TRACK_SUBSCRIBED",
                    "sessionId=${joined.sessionId} deviceId=${joined.deviceId} room=${credentials.roomName}"
                )
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && speakerBackendSession === state) {
                        inviteStatus.text = "Ready · waiting for the Host to start synchronized playback."
                        postStatus("Speaker ready. It will start with the other Speakers when the Host schedules playback.")
                    }
                }
            } catch (error: Exception) {
                room?.close()
                coordinator?.close()
                if (speakerBackendSession?.sessionId == joinedSessionId) {
                    speakerBackendSession = null
                }
                val cleanupApi = api
                val cleanupSessionId = joinedSessionId
                val cleanupDeviceToken = joinedDeviceToken
                if (cleanupApi != null && cleanupSessionId != null && cleanupDeviceToken != null) {
                    runCatching { cleanupApi.leaveSession(cleanupSessionId, cleanupDeviceToken) }
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && sessionGeneration.get() == attempt) {
                        inviteStatus.text = "Could not join the cloud session."
                        postStatus("Cloud Speaker join failed: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
                appendDiagnostic("CLOUD_SPEAKER_JOIN_FAILED", error.message ?: error.javaClass.simpleName)
            }
        }, "syncinit-cloud-speaker-join").apply { isDaemon = true }.start()
    }
    private fun createBackendCoordinator(
        api: BackendApiClient,
        sessionId: String,
        token: String,
        role: BackendSessionRole,
        label: String
    ): BackendSessionCoordinator = BackendSessionCoordinator(
        api = api,
        sessionId = sessionId,
        bearerToken = token,
        role = role,
        listener = object : BackendSessionCoordinatorListener {
            override fun onConnectionStateChanged(state: BackendConnectionState) {
                if (state == BackendConnectionState.CONNECTED) {
                    postStatus("$label backend connected.")
                } else if (state == BackendConnectionState.RECONNECT_WAIT ||
                    state == BackendConnectionState.FAILED
                ) {
                    postStatus("$label backend connection: ${state.name.lowercase()}.")
                }
                appendDiagnostic("BACKEND_STATE", "$label ${state.name}")
            }

            override fun onEvent(event: BackendEvent) {
                appendDiagnostic("BACKEND_EVENT", "$label ${event.type}")
                if (event.type == "session.snapshot") {
                    postStatus("$label backend session snapshot received.")
                } else if (role == BackendSessionRole.SPEAKER &&
                    event.type == "playback.scheduled"
                ) {
                    val startAtNs = event.longField("start_at_server_monotonic_ns")
                    val speaker = speakerBackendSession
                    if (startAtNs != null && speaker?.sessionId == sessionId) {
                        speaker.room.schedulePlayback(startAtNs)
                    }
                } else if (role == BackendSessionRole.SPEAKER &&
                    event.type == "playback.cancelled"
                ) {
                    speakerBackendSession?.takeIf { it.sessionId == sessionId }
                        ?.room?.cancelScheduledPlayback()
                }
            }

            override fun onFailure(error: Throwable) {
                appendDiagnostic(
                    "BACKEND_FAILURE",
                    "$label ${error.message ?: error.javaClass.simpleName}"
                )
            }
        }
    )

    private fun startSynchronizedPlayback() {
        val session = hostBackendSession ?: run {
            postStatus("Start the Host session before scheduling playback.")
            return
        }
        if (!synchronizedPlaybackStartInProgress.compareAndSet(false, true)) return
        synchronizedPlaybackButton.isEnabled = false
        postStatus("Checking Speaker readiness and scheduling a shared playback start…")
        Thread({
            try {
                val schedule = session.api.scheduleSynchronizedPlayback(
                    session.sessionId,
                    session.hostToken
                )
                check(hostBackendSession === session) { "Host session changed before playback start" }
                session.media.startSynchronizedPlayback()
                postStatus(
                    "Synchronized playback scheduled for ${schedule.speakerCount} Speaker(s)."
                )
            } catch (error: Exception) {
                synchronizedPlaybackStartInProgress.set(false)
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && hostBackendSession === session) {
                        synchronizedPlaybackButton.isEnabled = true
                    }
                }
                postStatus(
                    "Could not start synchronized playback: ${error.message ?: error.javaClass.simpleName}"
                )
            }
        }, "syncinit-playback-schedule").apply { isDaemon = true }.start()
    }

    private fun closeBackendHostState(session: HostBackendSession) {
        session.media.close()
        session.coordinator.close()
        runCatching { session.api.closeSession(session.sessionId, session.hostToken) }
    }

    private fun stopBackendHostSession() {
        val session = hostBackendSession ?: return
        hostBackendSession = null
        Thread({
            closeBackendHostState(session)
        }, "syncinit-cloud-host-close").apply { isDaemon = true }.start()
        if (::hostQrImage.isInitialized) {
            hostQrImage.setImageDrawable(null)
            hostQrImage.visibility = ImageView.GONE
        }
        if (::hostJoinCodeDetails.isInitialized) {
            hostJoinCodeDetails.text = "A cloud invitation will appear when the Host is connected."
        }
    }

    private fun stopBackendSpeakerSession() {
        val session = speakerBackendSession ?: return
        speakerBackendSession = null
        Thread({
            session.room.close()
            session.coordinator.close()
            runCatching { session.api.leaveSession(session.sessionId, session.deviceToken) }
        }, "syncinit-cloud-speaker-leave").apply { isDaemon = true }.start()
    }
    private fun stopHostForSourceChange(source: String) {
        if (hostBackendSession == null) return
        appendDiagnostic(
            "HOST_SOURCE_CHANGE",
            "Stopping the active Host before changing audio source to $source."
        )
        stopConnections(stopCapture = false)
        postStatus("Host stopped for audio source change. Select the source, then start hosting again.")
    }

    private fun stopConnections(stopCapture: Boolean = true) {
        sessionGeneration.incrementAndGet()
        synchronizedPlaybackStartInProgress.set(false)
        if (::synchronizedPlaybackButton.isInitialized) {
            synchronizedPlaybackButton.isEnabled = false
        }
        stopBackendHostSession()
        stopBackendSpeakerSession()
        if (stopCapture && selectedSystemAudio) {
            stopSystemAudioCapture("session-stopped")
            if (::audioSelectionDetails.isInitialized && selectedAudioUri == null) {
                audioSelectionDetails.text = "No file selected · diagnostic timing signal will be used."
            }
        }
        if (::hostDetails.isInitialized) hostDetails.text = "You are not hosting a cloud session."
        if (::hostQrImage.isInitialized) {
            hostQrImage.setImageDrawable(null)
            hostQrImage.visibility = ImageView.GONE
        }
        if (::hostJoinCodeDetails.isInitialized) {
            hostJoinCodeDetails.text = "A cloud invitation will appear when the Host is connected."
        }
        if (::inviteStatus.isInitialized) inviteStatus.text = "Ready to scan a Host invitation."
        if (::status.isInitialized) status.text = "Disconnected."
    }
    private fun postStatus(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed || !::status.isInitialized) return@runOnUiThread
            status.text = message
            appendDiagnostic("STATUS", message)
        }
    }
    private fun exportDiagnosticLog() {
        appendDiagnostic("ACTION", "User requested a diagnostic text export.")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, diagnosticLogStore.suggestedFileName())
        }
        try {
            startActivityForResult(intent, REQUEST_EXPORT_DIAGNOSTICS)
        } catch (error: Exception) {
            appendDiagnostic("EXPORT_FAILED", error.message ?: error.javaClass.simpleName)
            Toast.makeText(
                this,
                "No compatible document provider is available.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun clearDiagnosticLog() {
        synchronized(diagnosticEvents) { diagnosticEvents.clear() }
        diagnosticLogStore.clear()
        if (::debugLog.isInitialized) debugLog.text = "Diagnostic log cleared."
        appendDiagnostic("ACTION", "Diagnostic history cleared by the user.")
    }

    private fun appendDiagnostic(category: String, message: String) {
        if (::diagnosticLogStore.isInitialized) diagnosticLogStore.append(category, message)
        if (!::debugLog.isInitialized) return

        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(diagnosticEvents) {
            diagnosticEvents.addLast("$timestamp [$category] $message")
            while (diagnosticEvents.size > 240) diagnosticEvents.removeFirst()
        }

        // Backend, LiveKit and audio callbacks run on worker threads. Only render Views
        // on the main thread; compute the latest snapshot inside the UI task to avoid
        // displaying stale snapshots when callbacks arrive concurrently.
        runOnUiThread {
            if (isFinishing || isDestroyed || !::debugLog.isInitialized) return@runOnUiThread
            val snapshot = synchronized(diagnosticEvents) {
                diagnosticEvents.joinToString("\n")
            }
            debugLog.text = snapshot
        }
    }

    private fun sectionCard(
        parent: LinearLayout,
        eyebrow: String,
        title: String,
        description: String
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = panelBackground()
        }
        card.addView(TextView(this).apply {
            text = eyebrow
            textSize = 9f
            letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(56, 205, 225))
        })
        card.addView(TextView(this).apply {
            text = title
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.rgb(239, 244, 255))
            setPadding(0, dp(5), 0, dp(4))
        })
        card.addView(TextView(this).apply {
            text = description
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(Color.rgb(151, 166, 193))
        })
        parent.addView(card, sectionMargins(bottom = 13))
        return card
    }

    private fun featureChip(label: String) = TextView(this).apply {
        text = label
        textSize = 9f
        letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.rgb(149, 215, 231))
        setPadding(dp(10), dp(7), dp(10), dp(7))
        background = pillBackground(Color.rgb(12, 30, 44), Color.rgb(38, 105, 125))
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minHeight = dp(46)
        setPadding(dp(14), dp(5), dp(14), dp(5))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val isPrimary = label == "Start hosting" ||
            label == "Start synchronized playback" || label == "Join this session"
        val isDanger = label.startsWith("Stop session")
        background = when {
            isPrimary -> pillBackground(Color.rgb(35, 111, 224), Color.rgb(35, 111, 224))
            isDanger -> pillBackground(Color.rgb(44, 27, 38), Color.rgb(117, 58, 76))
            else -> pillBackground(Color.rgb(21, 31, 49), Color.rgb(48, 69, 99))
        }
        setTextColor(if (isDanger) Color.rgb(255, 177, 193) else Color.rgb(232, 241, 255))
        stateListAnimator = null
        elevation = 0f
        setOnClickListener { action() }
    }

    private fun panelBackground(
        fill: Int = Color.rgb(13, 19, 32)
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(fill)
        setStroke(dp(1), Color.rgb(35, 48, 70))
    }

    private fun insetBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(Color.rgb(9, 14, 25))
        setStroke(dp(1), Color.rgb(30, 42, 63))
    }

    private fun pillBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(999).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun sectionMargins(
        top: Int = 0,
        bottom: Int = 0
    ) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        stopConnections()
        SystemAudioCaptureBuffer.statusListener = null
        super.onDestroy()
    }
}
