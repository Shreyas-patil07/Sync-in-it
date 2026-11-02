package com.syncinit.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.syncinit.app.core.NativeCore
import com.syncinit.app.core.NativeSessionSmokeTest
import com.syncinit.app.core.NetworkHandshakeSmokeTest
import com.syncinit.app.network.MasterSessionHost
import com.syncinit.app.network.ReceiverSessionClient

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var foundationStatus: TextView
    private lateinit var hostDetails: TextView
    private lateinit var masterAddress: EditText
    private lateinit var sessionToken: EditText

    @Volatile private var masterHost: MasterSessionHost? = null
    @Volatile private var receiverClient: ReceiverSessionClient? = null

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
            text = "Create a local session on this phone. Share the IP address and 32-character token with one receiver on the same Wi-Fi network."
            textSize = 14f
        })
        content.addView(actionButton("Create session") { startHosting() })
        hostDetails = TextView(this).apply {
            text = "No active host session."
            textSize = 14f
            setPadding(0, 8, 0, 4)
        }
        content.addView(hostDetails)
        content.addView(actionButton("Copy invitation") { copyInvitation() })

        content.addView(sectionTitle("Join a session"))
        masterAddress = EditText(this).apply {
            hint = "Master IPv4 address (for example 192.168.1.10)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        content.addView(masterAddress)
        sessionToken = EditText(this).apply {
            hint = "32-character session token"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        content.addView(sessionToken)
        content.addView(actionButton("Join master") { joinMaster() })

        content.addView(sectionTitle("Connection status"))
        status = TextView(this).apply {
            text = "Idle · connect both phones to the same local Wi-Fi network."
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        content.addView(status)
        content.addView(actionButton("Stop / disconnect") { stopConnections() })

        content.addView(TextView(this).apply {
            text = "This build validates HELLO → WELCOME → AUDIO_HELLO over separate TCP channels and runs UDP clock acquisition using the shared C++ estimator. READY remains blocked until an audio buffer exists; streaming and playback are not enabled yet."
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

    private fun startHosting() {
        stopConnections()
        val service = MasterSessionHost(::postStatus)
        masterHost = service
        postStatus("Starting the local host…")
        Thread({
            try {
                val invitation = service.start()
                runOnUiThread {
                    if (isFinishing || isDestroyed || masterHost !== service) {
                        service.close()
                    } else {
                        hostDetails.text =
                            "Master: ${invitation.address}:${invitation.controlPort}\nToken: ${invitation.sessionToken}"
                        postStatus("Host is ready. Enter this address and token on the receiver phone.")
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

    private fun joinMaster() {
        stopConnections()
        try {
            val client = ReceiverSessionClient(::postStatus)
            receiverClient = client
            client.connect(
                host = masterAddress.text.toString().trim(),
                tokenHex = sessionToken.text.toString()
            )
        } catch (error: Exception) {
            postStatus("Could not join: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun copyInvitation() {
        val text = hostDetails.text.toString()
        if (text == "No active host session.") {
            postStatus("Create a host session first.")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Sync In It invitation", text))
        postStatus("Invitation copied.")
    }

    private fun stopConnections() {
        masterHost?.close()
        masterHost = null
        receiverClient?.close()
        receiverClient = null
        if (::hostDetails.isInitialized) hostDetails.text = "No active host session."
        if (::status.isInitialized) status.text = "Disconnected."
    }

    private fun postStatus(message: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed && ::status.isInitialized) status.text = message
        }
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 19f
        setPadding(0, 20, 0, 6)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
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
        stopConnections()
        super.onDestroy()
    }
}
