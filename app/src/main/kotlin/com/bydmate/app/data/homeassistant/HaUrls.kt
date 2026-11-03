package com.bydmate.app.data.homeassistant

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Address arithmetic for the Home Assistant Mobile App API. Pure functions, so the rules
 * (https only, which webhook address wins) are testable without a server.
 */
object HaUrls {

    private const val REGISTRATIONS_PATH = "api/mobile_app/registrations"
    private const val API_ROOT_PATH = "api/"
    private const val WEBHOOK_PATH = "api/webhook"

    /**
     * Turns what the user typed into the instance root, or null when it is not usable.
     * https only: the app allows cleartext for loopback alone (network_security_config.xml), and
     * the long-lived token must never cross a network in the clear. A pasted `/api` suffix or a
     * trailing slash is dropped so `https://ha.example/api/` and `https://ha.example` agree.
     */
    fun normalizeBase(raw: String): HttpUrl? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "https") return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (segments.lastOrNull() == "api") segments.removeAt(segments.lastIndex)
        return url.newBuilder()
            .encodedPath("/")
            .apply { segments.forEach { addPathSegment(it) } }
            .query(null)
            .fragment(null)
            .build()
    }

    fun registrationUrl(base: HttpUrl): HttpUrl = base.resolveUnder(REGISTRATIONS_PATH)

    /** `GET /api/` answers `{"message": "API running."}` to a valid token, 401 otherwise. */
    fun apiRootUrl(base: HttpUrl): HttpUrl = base.resolveUnder(API_ROOT_PATH)

    /**
     * Where sensor updates go. Home Assistant's own rule for mobile apps: the Nabu Casa cloudhook
     * when there is one (it works from any network), else the remote UI address, else the instance
     * URL the user entered. Non-https candidates are skipped for the same reason as in [normalizeBase].
     */
    fun webhookUrl(base: HttpUrl, registration: HaRegistration): HttpUrl? {
        registration.cloudhookUrl?.toHttpsUrlOrNull()?.let { return it }
        val root = registration.remoteUiUrl?.let(::normalizeBase) ?: base
        return root.newBuilder()
            .addPathSegments(WEBHOOK_PATH)
            .addPathSegment(registration.webhookId)
            .build()
    }

    private fun HttpUrl.resolveUnder(path: String): HttpUrl =
        newBuilder().addPathSegments(path).build()

    private fun String.toHttpsUrlOrNull(): HttpUrl? =
        trim().toHttpUrlOrNull()?.takeIf { it.scheme == "https" }
}
