package com.syncinit.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.syncinit.app.core.NativeCore
import com.syncinit.app.core.NativeSessionSmokeTest
import com.syncinit.app.core.NetworkHandshakeSmokeTest
import com.syncinit.app.network.LanSessionBrowser
import com.syncinit.app.network.MasterSessionHost
import com.syncinit.app.network.NearbySession
import com.syncinit.app.network.ReceiverSessionClient

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var foundationStatus: TextView
    private lateinit var hostDetails: TextView
    private lateinit var discoveryStatus: TextView
    private lateinit var nearbySessionsContainer: LinearLayout

    @Volatile private var masterHost: MasterSessionHost? = null
    @Volatile private var receiverClient: ReceiverSessionClient? = null
    @Volatile private var hostServiceName: String? = null
    @Volatile private var nearbyBrowser: LanSessionBrowser? = null
    private val nearbySessions = LinkedHashMap<String, NearbySession>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 28, 24, 28)
        }

        content.addView(TextView(this).apply {
            text = "Sync In It"
            textSize = 30f
        })
        foundationStatus = TextView(this).apply {
            text = "Running native protocol and TCP/UDP loopback checks…"
            textSize = 14f
            setPadding(0, 8, 0, 20)
        }
        content.addView(foundationStatus)

        content.addView(sectionTitle("Host a session"))
        content.addView(TextView(this).apply {
            text = "Make this phone visible to other Sync In It devices on the same Wi-Fi network."
            textSize = 14f
        })
        content.addView(actionButton("Start hosting") { startHosting() })
        hostDetails = TextView(this).apply {
            text = "You are not hosting a session."
            textSize = 14f
            setPadding(0, 8, 0, 4)
        }
        content.addView(hostDetails)

        content.addView(sectionTitle("Nearby sessions"))
        content.addView(TextView(this).apply {
            text = "Choose a phone to join. Hosts appear automatically when they are advertising on the same local network."
            textSize = 14f
        })
        content.addView(actionButton("Refresh nearby sessions") { scanNearbySessions() })
        discoveryStatus = TextView(this).apply {
            text = "Searching when this screen is open…"
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        content.addView(discoveryStatus)
        nearbySessionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(nearbySessionsContainer)

        content.addView(sectionTitle("Connection status"))
        status = TextView(this).apply {
            text = "Idle · connect both phones to the same private Wi-Fi network."
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        content.addView(status)
        content.addView(actionButton("Stop session / disconnect") { stopConnections() })

        content.addView(TextView(this).apply {
            text = "Nearby discovery uses your local Wi-Fi only. Use a trusted private network. The host sends a generated 1 kHz timing-click test stream; receivers prebuffer PCM16 and play it through AudioTrack. User-selected audio and scheduled group playback are not implemented yet."
            textSize = 12f
            setPadding(0, 24, 0, 0)
        })

        val scroll = ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }
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

    private fun startHosting() {
        stopConnections()
        val service = MasterSessionHost(applicationContext, ::postStatus)
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
                        hostDetails.text =
                            "Visible as ${invitation.serviceName}\nWaiting for one receiver. A 1 kHz timing-click test stream will start after it joins."
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
        val client = ReceiverSessionClient(::postStatus)
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
            discoveryStatus.text = "No hosts found yet. Keep this screen open and check that the host is on the same Wi-Fi."
            return
        }
        discoveryStatus.text = "${sessions.size} nearby session(s) found. Tap one to join."
        for (session in sessions) {
            nearbySessionsContainer.addView(
                actionButton("${session.serviceName}\n${session.hostAddress} · Tap to join") {
                    joinNearby(session)
                }
            )
        }
    }

    private fun stopDiscovery() {
        nearbyBrowser?.close()
        nearbyBrowser = null
    }

    private fun stopConnections() {
        masterHost?.close()
        masterHost = null
        receiverClient?.close()
        receiverClient = null
        hostServiceName = null
        if (::hostDetails.isInitialized) hostDetails.text = "You are not hosting a session."
        if (::status.isInitialized) status.text = "Disconnected."
        if (::nearbySessionsContainer.isInitialized) renderNearbySessions()
    }

    private fun postStatus(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed || !::status.isInitialized) return@runOnUiThread
            status.text = message

            val hostEnded = message.startsWith("Receiver sent LEAVE.") ||
                message.startsWith("Receiver control connection closed.") ||
                message.startsWith("Admitted receiver disconnected:")
            if (hostEnded && masterHost != null) {
                masterHost = null
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
            }
        }
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 19f
        setPadding(0, 20, 0, 6)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun runStartupSmokeTests() {
        Thread({
            val result = nativeStatus()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) foundationStatus.text = result
            }
        }, "syncinit-startup-smoke-tests").apply { isDaemon = true }.start()
    }

    private fun nativeStatus(): String = try {
        "Native C++ core API ${NativeCore.nativeCoreVersion()} · ${NativeSessionSmokeTest.run()} · ${NetworkHandshakeSmokeTest.run()}"
    } catch (error: LinkageError) {
        "Native C++ core failed to load: ${error.message ?: "unknown linker error"}"
    } catch (error: Exception) {
        "Native protocol/session smoke test failed: ${error.message ?: "unknown native error"}"
    }

    override fun onDestroy() {
        stopDiscovery()
        stopConnections()
        super.onDestroy()
    }
}
