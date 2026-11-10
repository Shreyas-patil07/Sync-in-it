// SPDX-License-Identifier: Apache-2.0
package com.syncinit.app

import android.graphics.Bitmap
import org.json.JSONObject
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

data class BackendInvite(
    val joinCode: String,
    val lanFingerprint: String
)

object BackendInviteCodec {
    private const val TYPE = "sync-in-it-invite"
    private const val VERSION = 1
    private val joinCodePattern = Regex("[A-HJ-NP-Z2-9]{8}")
    private val fingerprintPattern = Regex("[0-9A-F]{8}")

    fun encode(invite: BackendInvite): String {
        val code = invite.joinCode.trim().uppercase()
        val fingerprint = invite.lanFingerprint.trim().uppercase()
        require(joinCodePattern.matches(code)) { "invalid backend join code" }
        require(fingerprintPattern.matches(fingerprint)) { "invalid LAN fingerprint" }
        return JSONObject()
            .put("type", TYPE)
            .put("version", VERSION)
            .put("join_code", code)
            .put("lan_fingerprint", fingerprint)
            .toString()
    }

    fun decode(raw: String): BackendInvite {
        require(raw.length <= 512) { "QR invitation is too large" }
        val json = JSONObject(raw)
        require(json.optString("type") == TYPE) { "This QR code is not a Sync In It invitation" }
        require(json.optInt("version", -1) == VERSION) { "Unsupported Sync In It invitation version" }
        val code = json.getString("join_code").trim().uppercase()
        val fingerprint = json.getString("lan_fingerprint").trim().uppercase()
        require(joinCodePattern.matches(code)) { "Invalid Sync In It join code" }
        require(fingerprintPattern.matches(fingerprint)) { "Invalid local Host fingerprint" }
        return BackendInvite(code, fingerprint)
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
