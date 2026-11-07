// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-private rolling diagnostic log with a user-initiated plain-text export. */
class DiagnosticLogStore(context: Context) {
    private val appContext = context.applicationContext
    private val logFile = File(appContext.filesDir, "diagnostics/sync-in-it-diagnostics.txt")

    init {
        synchronized(this) {
            logFile.parentFile?.mkdirs()
            if (!logFile.exists()) {
                logFile.writeText(header(), StandardCharsets.UTF_8)
            }
            appendInternal(
                "APP_INSTANCE",
                "startedAt=${timestamp()} appVersion=${appVersion()} " +
                    "device=${Build.MANUFACTURER} ${Build.MODEL} " +
                    "android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}"
            )
        }
    }

    @Synchronized
    fun append(category: String, message: String) {
        logFile.parentFile?.mkdirs()
        if (!logFile.exists()) logFile.writeText(header(), StandardCharsets.UTF_8)
        appendInternal(category, message)
    }

    @Synchronized
    fun snapshot(): String {
        if (!logFile.exists()) return header()
        return logFile.readText(StandardCharsets.UTF_8)
    }

    @Synchronized
    fun clear() {
        logFile.parentFile?.mkdirs()
        logFile.writeText(header(), StandardCharsets.UTF_8)
        appendInternal("ACTION", "Diagnostic log cleared by the user.")
    }

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "SyncInIt-diagnostics-$stamp.txt"
    }

    private fun appendInternal(category: String, message: String) {
        val safeCategory = category.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val safeMessage = redactSecrets(message)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\n", "\n    ")
        logFile.appendText(
            "${timestamp()} [${safeCategory}] ${safeMessage}\n",
            StandardCharsets.UTF_8
        )
        trimIfNeeded()
    }

    private fun trimIfNeeded() {
        if (logFile.length() <= MAX_LOG_BYTES) return

        val bytes = logFile.readBytes()
        val keepFrom = (bytes.size - RETAIN_AFTER_TRIM_BYTES).coerceAtLeast(0)
        var start = keepFrom
        while (start < bytes.size && bytes[start] != NEWLINE_BYTE) start++
        if (start < bytes.size) start++

        val tail = String(bytes, start, bytes.size - start, StandardCharsets.UTF_8)
        val trimmed = header() +
            "[LOG_TRUNCATED] Older entries were removed to keep the app-private log bounded.\n" +
            tail
        logFile.writeText(trimmed, StandardCharsets.UTF_8)
    }

    private fun header(): String = buildString {
        appendLine("Sync In It diagnostic report")
        appendLine("schemaVersion=1")
        appendLine("createdAt=${timestamp()}")
        appendLine("appVersion=${appVersion()}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("androidVersion=${Build.VERSION.RELEASE}")
        appendLine("androidApi=${Build.VERSION.SDK_INT}")
        appendLine("privacy=No audio is included. Token, password, secret and API-key values are redacted.")
        appendLine("================================================================")
    }

    private fun appVersion(): String = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName ?: "unknown"} ($code)"
    } catch (_: Exception) {
        "unknown"
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            .format(Date())

    private fun redactSecrets(value: String): String =
        SECRET_PATTERN.replace(value) { match ->
            "${match.groupValues[1]}[REDACTED]"
        }

    private companion object {
        const val MAX_LOG_BYTES = 4L * 1024L * 1024L
        const val RETAIN_AFTER_TRIM_BYTES = 2 * 1024 * 1024
        val NEWLINE_BYTE = '\n'.code.toByte()
        val SECRET_PATTERN = Regex(
            "(?i)(\\b(?:session[_ -]?token|tokenHex|token|password|secret|api[_-]?key)\\b\\s*[=:]\\s*)([^\\s,;]+)"
        )
    }
}
