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
import com.syncinit.app.core.NativeCore
import com.syncinit.app.diagnostics.DiagnosticLogStore
import com.syncinit.app.core.NativeSessionSmokeTest
import com.syncinit.app.core.NetworkHandshakeSmokeTest
import com.syncinit.app.audio.PcmAudioSmokeTest
import com.syncinit.app.audio.SystemAudioCaptureBuffer
import com.syncinit.app.audio.SystemAudioCaptureService
import com.syncinit.app.audio.DacClockSmokeTest
import com.syncinit.app.network.LanSessionBrowser
import com.syncinit.app.network.NetworkDiagnostics
import com.syncinit.app.network.MasterSessionHost
import com.syncinit.app.network.NearbySession
import com.syncinit.app.network.ReceiverSessionClient

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var foundationStatus: TextView
    private lateinit var hostDetails: TextView
    private lateinit var audioSelectionDetails: TextView
    private lateinit var discoveryStatus: TextView
    private lateinit var nearbySessionsContainer: LinearLayout
    private lateinit var debugLog: TextView
    private lateinit var diagnosticLogStore: DiagnosticLogStore
    private val diagnosticEvents = ArrayDeque<String>()

    companion object {
        private const val REQUEST_EXPORT_DIAGNOSTICS = 4102
        private const val REQUEST_PICK_AUDIO = 4103
        private const val REQUEST_CAPTURE_SYSTEM_AUDIO = 4104
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 4105
    }

    @Volatile private var masterHost: MasterSessionHost? = null
    @Volatile private var receiverClient: ReceiverSessionClient? = null
    @Volatile private var hostServiceName: String? = null
    @Volatile private var nearbyBrowser: LanSessionBrowser? = null
    @Volatile private var selectedAudioUri: Uri? = null
    @Volatile private var selectedAudioName: String? = null
    @Volatile private var selectedSystemAudio = false
    private val nearbySessions = LinkedHashMap<String, NearbySession>()

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
            text = "OFFLINE AUDIO · ONE SHARED CLOCK"
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
            text = "One timeline.\nEvery phone in sync."
            textSize = 27f
            setTextColor(Color.rgb(241, 245, 255))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = -0.035f
            setPadding(dp(2), dp(23), dp(2), dp(7))
        }.also { content.addView(it) }

        TextView(this).apply {
            text = "Create a local speaker group over your phone hotspot or private Wi-Fi. No internet connection is needed."
            textSize = 14f
            setTextColor(Color.rgb(157, 169, 194))
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(2), 0, dp(2), dp(14))
        }.also { content.addView(it) }

        val featureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        featureRow.addView(featureChip("NO INTERNET"), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) })
        featureRow.addView(featureChip("UP TO ${PreviewConfig.MAX_SPEAKERS} SPEAKERS"))
        content.addView(featureRow, sectionMargins(bottom = 18))

        foundationStatus = TextView(this).apply {
            text = "Running native protocol and TCP/UDP loopback checks…"
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
            description = "Choose a local audio file to stream from this phone to every connected Speaker. The diagnostic timing signal remains available for sync tests."
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
        hostDetails = TextView(this).apply {
            text = "You are not hosting a session."
            textSize = 13f
            setTextColor(Color.rgb(159, 174, 198))
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = insetBackground()
        }
        hostCard.addView(hostDetails)

        val nearbyCard = sectionCard(
            parent = content,
            eyebrow = "02 / JOIN",
            title = "Nearby sessions",
            description = "Hosts on the same local network appear here. Select a session to connect this phone as a Speaker."
        )
        nearbyCard.addView(actionButton("Refresh nearby sessions") { scanNearbySessions() }, sectionMargins(top = 12, bottom = 8))
        discoveryStatus = TextView(this).apply {
            text = "Searching when this screen is open…"
            textSize = 12f
            setTextColor(Color.rgb(142, 160, 190))
            setPadding(dp(2), dp(7), dp(2), dp(10))
        }
        nearbyCard.addView(discoveryStatus)
        nearbySessionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        nearbyCard.addView(nearbySessionsContainer)

        val connectionCard = sectionCard(
            parent = content,
            eyebrow = "03 / SESSION",
            title = "Connection status",
            description = "Live host, clock and receiver updates appear here."
        )
        status = TextView(this).apply {
            text = "Idle · connect all phones to the same hotspot or private Wi-Fi network."
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
            title = "Network debugger",
            description = "Capture network, clock and session events. Save the full app-private diagnostic history as a plain-text report."
        )
        val diagnosticActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        diagnosticActions.addView(actionButton("Capture local network state") { captureNetworkSnapshot() })
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
            text = "PLAYBACK NOTE\nThe Host can stream a selected local file, capture eligible Android app playback with explicit system consent, or use the diagnostic signal. Both media sources feed the same 48 kHz stereo PCM16 timeline and TCP audio channel. Android may block capture for protected or capture-restricted apps. The Host waits for clock-stable, buffered Speakers before coordinating the shared start anchor."
            textSize = 11f
            setTextColor(Color.rgb(119, 137, 168))
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(3), dp(18), dp(3), 0)
        }.also { content.addView(it) }

        setContentView(scroll)
        runStartupSmokeTests()
    }

    override fun onStart() {
        super.onStart()
        scanNearbySessions()
    }

    override fun onStop() {
        stopDiscovery()
        super.onStop()
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_AUDIO) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
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
        stopHostForSourceChange("system-audio")
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
        stopHostForSourceChange("local-file")
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
        val audioUri = selectedAudioUri
        val audioName = selectedAudioName
        val useSystemAudio = selectedSystemAudio
        val service = MasterSessionHost(applicationContext, ::postStatus, audioUri, audioName, useSystemAudio)
        masterHost = service
        postStatus("Starting a discoverable session…")
        Thread({
            try {
                val invitation = service.start()
                runOnUiThread {
                    if (isFinishing || isDestroyed || masterHost !== service) {
                        service.close()
                    } else {
                        hostServiceName = invitation.serviceName
                        val sourceDescription = when {
                            useSystemAudio -> "Capturing eligible Android app playback"
                            audioUri != null -> "Sharing local file: ${audioName ?: "selected audio"} (loops at end-of-file)"
                            else -> "Diagnostic 1 kHz test signal"
                        }
                        hostDetails.text =
                            "Visible as ${invitation.serviceName}\n$sourceDescription\nWaiting for up to ${PreviewConfig.MAX_SPEAKERS} Speakers."
                        postStatus("Session created. Nearby phones can see this device and tap to join.")
                        renderNearbySessions()
                    }
                }
            } catch (error: Exception) {
                if (masterHost === service) {
                    masterHost = null
                    postStatus("Could not host: ${error.message ?: error.javaClass.simpleName}")
                }
                service.close()
            }
        }, "syncinit-master-startup").apply { isDaemon = true }.start()
    }

    private fun joinNearby(session: NearbySession) {
        stopConnections()
        stopDiscovery()
        appendDiagnostic("JOIN_TARGET", "service=${session.serviceName} host=${session.hostAddress} controlPort=${session.controlPort}")
        val client = ReceiverSessionClient(applicationContext, ::postStatus)
        receiverClient = client
        postStatus("Joining ${session.serviceName}…")
        try {
            client.connect(
                host = session.hostAddress,
                tokenHex = session.sessionToken,
                controlPort = session.controlPort
            )
        } catch (error: Exception) {
            postStatus("Could not join: ${error.message ?: error.javaClass.simpleName}")
            client.close()
            receiverClient = null
        }
    }

    private fun scanNearbySessions() {
        stopDiscovery()
        nearbySessions.clear()
        renderNearbySessions()
        discoveryStatus.text = "Searching this Wi-Fi network for Sync In It hosts…"

        try {
            val browser = LanSessionBrowser(
                context = applicationContext,
                onSessionFound = { session ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed || session.serviceName == hostServiceName) return@runOnUiThread
                        nearbySessions[session.serviceName] = session
                        appendDiagnostic("NSD_SESSION", "resolved service=${session.serviceName} endpoint=${session.hostAddress}:${session.controlPort}")
                        renderNearbySessions()
                    }
                },
                onSessionLost = { serviceName ->
                    runOnUiThread {
                        nearbySessions.remove(serviceName)
                        renderNearbySessions()
                    }
                },
                onStatus = ::postDiscoveryStatus
            )
            nearbyBrowser = browser
            browser.start()
        } catch (error: Exception) {
            nearbyBrowser = null
            discoveryStatus.text = "Nearby discovery unavailable: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun renderNearbySessions() {
        if (!::nearbySessionsContainer.isInitialized) return
        nearbySessionsContainer.removeAllViews()
        val sessions = nearbySessions.values.sortedBy { it.serviceName }
        if (sessions.isEmpty()) {
            discoveryStatus.text = "No hosts found yet. Keep this screen open and check that all phones are on the same hotspot or local Wi-Fi network."
            return
        }
        discoveryStatus.text = "${sessions.size} nearby session(s) found · tap to join."
        for (session in sessions) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = insetBackground()
            }
            item.addView(TextView(this).apply {
                text = session.serviceName
                textSize = 14f
                setTextColor(Color.rgb(235, 242, 255))
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            })
            item.addView(TextView(this).apply {
                text = "${session.hostAddress} · Port ${session.controlPort}"
                textSize = 11f
                setTextColor(Color.rgb(136, 157, 188))
                setPadding(0, dp(4), 0, dp(9))
            })
            item.addView(actionButton("Join this session") { joinNearby(session) })
            nearbySessionsContainer.addView(item, sectionMargins(bottom = 8))
        }
    }

    private fun stopDiscovery() {
        nearbyBrowser?.close()
        nearbyBrowser = null
    }

    private fun stopHostForSourceChange(source: String) {
        if (masterHost == null) return
        appendDiagnostic(
            "HOST_SOURCE_CHANGE",
            "Stopping the active Host before changing audio source to $source."
        )
        stopConnections(stopCapture = false)
        postStatus("Host stopped for audio source change. Select the source, then start hosting again.")
    }

    private fun stopConnections(stopCapture: Boolean = true) {
        masterHost?.close()
        masterHost = null
        receiverClient?.close()
        receiverClient = null
        if (stopCapture && selectedSystemAudio) {
            stopSystemAudioCapture("session-stopped")
            if (::audioSelectionDetails.isInitialized && selectedAudioUri == null) {
                audioSelectionDetails.text = "No file selected · diagnostic timing signal will be used."
            }
        }
        hostServiceName = null
        if (::hostDetails.isInitialized) hostDetails.text = "You are not hosting a session."
        if (::status.isInitialized) status.text = "Disconnected."
        if (::nearbySessionsContainer.isInitialized) renderNearbySessions()
    }

    private fun postStatus(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed || !::status.isInitialized) return@runOnUiThread
            status.text = message
            appendDiagnostic("STATUS", message)

            if (::hostDetails.isInitialized && masterHost != null &&
                (message.startsWith("Speaker connected") ||
                    message.startsWith("Speaker disconnected") ||
                    message.startsWith("Host is full"))
            ) {
                hostDetails.text = "${hostServiceName ?: "Hosting"}\n$message"
            }

            val hostEnded = message.startsWith("Receiver sent LEAVE.") ||
                message.startsWith("Receiver control connection closed.") ||
                message.startsWith("Admitted receiver disconnected:")
            if (hostEnded && masterHost != null) {
                masterHost = null
                if (selectedSystemAudio) stopSystemAudioCapture("host-session-ended")
                if (::hostDetails.isInitialized) {
                    hostDetails.text = "Host session ended. Tap Start hosting to advertise again."
                }
                renderNearbySessions()
            }
        }
    }

    private fun postDiscoveryStatus(message: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::discoveryStatus.isInitialized) {
                discoveryStatus.text = message
                appendDiagnostic("DISCOVERY", message)
            }
        }
    }

    private fun captureNetworkSnapshot() {
        appendDiagnostic("ACTION", "Capturing active Android network snapshot.")
        Thread({
            val snapshot = NetworkDiagnostics.networkSnapshot(applicationContext)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) appendDiagnostic("NETWORK_SNAPSHOT", snapshot)
            }
        }, "syncinit-network-diagnostics").apply { isDaemon = true }.start()
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
            debugLog.text = diagnosticEvents.joinToString("\n")
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
        val isPrimary = label == "Start hosting" || label == "Join this session"
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

    private fun runStartupSmokeTests() {
        Thread({
            val result = nativeStatus()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) foundationStatus.text = result
            }
        }, "syncinit-startup-smoke-tests").apply { isDaemon = true }.start()
    }

    private fun nativeStatus(): String = try {
        "Native C++ core API ${NativeCore.nativeCoreVersion()} · ${NativeSessionSmokeTest.run()} · ${NetworkHandshakeSmokeTest.run()} · ${PcmAudioSmokeTest.run()} · ${DacClockSmokeTest.run()}"
    } catch (error: LinkageError) {
        "Native C++ core failed to load: ${error.message ?: "unknown linker error"}"
    } catch (error: Exception) {
        "Native protocol/session smoke test failed: ${error.message ?: "unknown native error"}"
    }

    override fun onDestroy() {
        stopDiscovery()
        stopConnections()
        SystemAudioCaptureBuffer.statusListener = null
        super.onDestroy()
    }
}
