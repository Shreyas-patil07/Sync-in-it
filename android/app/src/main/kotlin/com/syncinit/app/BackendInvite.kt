// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app

import android.graphics.Bitmap
import org.json.JSONObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

data class BackendInvite(
    val sessionId: String,
    val joinCode: String
)

object BackendInviteCodec {
    private const val TYPE = "sync-in-it-invite"
    private const val VERSION = 2
    private val joinCodePattern = Regex("[A-HJ-NP-Z2-9]{8}")
    private val sessionIdPattern = Regex(
        "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    )

    fun encode(invite: BackendInvite): String {
        val sessionId = invite.sessionId.trim().lowercase()
        val code = invite.joinCode.trim().uppercase()
        require(sessionIdPattern.matches(sessionId)) { "invalid backend session ID" }
        require(joinCodePattern.matches(code)) { "invalid backend join code" }
        return JSONObject()
            .put("type", TYPE)
            .put("version", VERSION)
            .put("session_id", sessionId)
            .put("join_code", code)
            .toString()
    }

    fun decode(raw: String): BackendInvite {
        require(raw.length <= 512) { "QR invitation is too large" }
        val json = JSONObject(raw)
        require(json.optString("type") == TYPE) { "This QR code is not a Sync In It invitation" }
        require(json.optInt("version", -1) == VERSION) { "Unsupported Sync In It invitation version" }
        val sessionId = json.getString("session_id").trim().lowercase()
        val code = json.getString("join_code").trim().uppercase()
        require(sessionIdPattern.matches(sessionId)) { "Invalid backend session ID" }
        require(joinCodePattern.matches(code)) { "Invalid Sync In It join code" }
        return BackendInvite(sessionId, code)
    }
}

object BackendInviteQr {
    fun createBitmap(payload: String, sizePx: Int = 720): Bitmap {
        require(sizePx in 256..1_024)
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints
        )
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bitmap
    }
}
