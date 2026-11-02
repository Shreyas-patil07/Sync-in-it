package com.syncinit.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.syncinit.app.core.NativeCore

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Sync In It"
            textSize = 32f
        }
        val status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 20, 0, 0)
            text = nativeStatus()
        }
        val note = TextView(this).apply {
            textSize = 14f
            setPadding(0, 24, 0, 0)
            text = "Android foundation build. Network transport and the native session core are being integrated. Audio playback and multi-device synchronization are not enabled yet."
        }

        root.addView(title)
        root.addView(status)
        root.addView(note)
        setContentView(root)
    }

    private fun nativeStatus(): String = try {
        "Native C++ core loaded · API ${NativeCore.nativeCoreVersion()}"
    } catch (error: UnsatisfiedLinkError) {
        "Native C++ core failed to load: ${error.message ?: "unknown linker error"}"
    }
}
