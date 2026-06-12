package io.meshcheck.data.enrollment

import android.util.Base64
import org.json.JSONObject

/**
 * The decoded contents of a MeshCheck enrollment QR code.
 *
 * The dashboard QR is not a bare token: it is a small JSON envelope (base64
 * encoded) that names *which deployment* this device is joining — its REST
 * [apiBaseUrl] and its agent [gatewayUrl] — alongside the single-use [token].
 * The app is built against `wss://gateway.meshcheck.io` only as a fallback
 * default; the QR is the source of truth for where to actually connect.
 */
data class EnrollmentPayload(
    val apiBaseUrl: String,
    val gatewayUrl: String,
    val token: String,
)

/** Parses the scanned enrollment QR string into an [EnrollmentPayload]. */
object EnrollmentQr {

    private const val EXPECTED_TYPE = "meshcheck-enroll"
    private const val SUPPORTED_VERSION = 1

    // The "pair this phone" deep link is meshcheck://enroll#<base64>. The intent
    // path strips this to the fragment itself, but a user who pastes the whole
    // link into the "Paste a pairing code" field hands us the full URL.
    private const val DEEP_LINK_PREFIX = "meshcheck://enroll#"

    /**
     * Decodes a scanned QR string, or returns null if it is not a MeshCheck
     * enrollment code of a version this app understands. Accepts base64(JSON)
     * (the QR / copied code), raw JSON (handy for testing), or a full
     * `meshcheck://enroll#…` deep-link URL.
     */
    fun parse(scanned: String): EnrollmentPayload? {
        val candidate = scanned.trim().removePrefix(DEEP_LINK_PREFIX)
        val json = decodeJson(candidate) ?: return null
        if (json.optString("typ") != EXPECTED_TYPE) return null
        if (json.optInt("v", -1) != SUPPORTED_VERSION) return null

        val api = json.optString("api").trim().trimEnd('/')
        val gateway = json.optString("gateway").trim()
        val token = json.optString("token").trim()
        if (api.isEmpty() || gateway.isEmpty() || token.isEmpty()) return null

        return EnrollmentPayload(apiBaseUrl = api, gatewayUrl = gateway, token = token)
    }

    private fun decodeJson(text: String): JSONObject? {
        // Raw JSON (handy when pasting a decoded payload by hand).
        runCatching { return JSONObject(text) }
        // Otherwise base64(JSON) — try the standard and URL-safe alphabets.
        for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            runCatching {
                val decoded = String(Base64.decode(text, flags), Charsets.UTF_8)
                return JSONObject(decoded)
            }
        }
        return null
    }
}

/**
 * Reads the `nid` (Node id) claim out of a device-enrollment JWT *without*
 * verifying its signature — the app cannot verify the platform's HMAC, and does
 * not need to: the gateway verifies the token on connect. The claim is read
 * only to record which Node this device is. Returns null if the token is not a
 * decodable JWT or carries no `nid`.
 */
fun nodeIdFromDeviceToken(token: String): String? {
    val parts = token.split(".")
    if (parts.size < 2) return null
    return runCatching {
        val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        JSONObject(String(payload, Charsets.UTF_8)).optString("nid").ifBlank { null }
    }.getOrNull()
}
