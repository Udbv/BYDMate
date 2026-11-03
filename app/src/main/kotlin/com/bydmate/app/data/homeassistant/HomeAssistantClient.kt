package com.bydmate.app.data.homeassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** How one Home Assistant call ended; every network failure maps here, nothing throws. */
sealed interface HaResponse {
    data class Ok(val body: String) : HaResponse

    /** 401/403: the token is wrong or revoked. */
    data object Unauthorized : HaResponse

    /** 410 on the webhook: the user deleted the integration in HA, so register again. */
    data object Gone : HaResponse

    data class HttpError(val code: Int) : HaResponse

    /** No answer at all: offline, DNS, TLS. The message never contains the token. */
    data class NetworkError(val message: String) : HaResponse
}

/**
 * Plain OkHttp transport for [HomeAssistantPublisher]. The long-lived token travels only in the
 * Authorization header and is never logged; webhook calls carry no token at all, the webhook id
 * in the path is the credential.
 */
@Singleton
class HomeAssistantClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    /** `GET /api/`: the cheapest call that proves both the address and the token. */
    suspend fun checkApi(base: HttpUrl, token: String): HaResponse =
        execute(authorized(Request.Builder().url(HaUrls.apiRootUrl(base)).get(), token))

    suspend fun register(base: HttpUrl, token: String, body: JSONObject): HaResponse =
        execute(authorized(Request.Builder().url(HaUrls.registrationUrl(base)).post(body.toBody()), token))

    suspend fun postWebhook(url: HttpUrl, body: JSONObject): HaResponse =
        execute(Request.Builder().url(url).post(body.toBody()).build())

    private fun authorized(builder: Request.Builder, token: String): Request =
        builder.header("Authorization", "Bearer ${token.trim()}").build()

    private suspend fun execute(request: Request): HaResponse = withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> HaResponse.Ok(response.body?.string().orEmpty())
                    response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN -> HaResponse.Unauthorized
                    response.code == HTTP_GONE -> HaResponse.Gone
                    else -> HaResponse.HttpError(response.code)
                }
            }
        } catch (e: IOException) {
            HaResponse.NetworkError(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }

    private fun JSONObject.toBody() = toString().toRequestBody(JSON_MEDIA_TYPE)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_GONE = 410
    }
}
