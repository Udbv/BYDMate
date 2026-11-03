package com.bydmate.app.data.homeassistant

import org.json.JSONObject

/**
 * What `POST /api/mobile_app/registrations` hands back. [webhookId] is all later calls need;
 * whoever knows it can write to this device, so it is treated as a secret (never logged).
 */
data class HaRegistration(
    val webhookId: String,
    val cloudhookUrl: String? = null,
    val remoteUiUrl: String? = null,
) {
    companion object {
        /** Null when the body is not the registration answer (no webhook id, not JSON). */
        fun parse(body: String): HaRegistration? {
            // A proxy error page instead of JSON is an expected answer, not a fault.
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val webhookId = json.optString("webhook_id").trim()
            if (webhookId.isEmpty()) return null
            return HaRegistration(
                webhookId = webhookId,
                cloudhookUrl = json.optNullableString("cloudhook_url"),
                remoteUiUrl = json.optNullableString("remote_ui_url"),
            )
        }

        // optString turns JSON null into the text "null"; HA sends null for a missing cloudhook.
        private fun JSONObject.optNullableString(key: String): String? =
            if (isNull(key)) null else optString(key).trim().ifEmpty { null }
    }
}
