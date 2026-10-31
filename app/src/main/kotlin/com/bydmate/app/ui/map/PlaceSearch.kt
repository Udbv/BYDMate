package com.bydmate.app.ui.map

import com.bydmate.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/** A place found by name. */
data class FoundPlace(val name: String, val address: String, val lat: Double, val lon: Double)

/**
 * Place search on OpenStreetMap's Nominatim: one request per search the driver submits (its
 * usage policy asks for no more than one a second and a real User-Agent), results near the car
 * first through a view box around it, in the app's language.
 */
@Singleton
class PlaceSearch @Inject constructor(private val http: OkHttpClient) {

    suspend fun search(query: String, nearLat: Double?, nearLon: Double?, language: String): Result<List<FoundPlace>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = ENDPOINT.toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("format", "jsonv2")
                    .addQueryParameter("limit", LIMIT.toString())
                    .addQueryParameter("accept-language", language)
                    .apply {
                        if (nearLat != null && nearLon != null) {
                            addQueryParameter(
                                "viewbox",
                                "${nearLon - BOX_DEG},${nearLat + BOX_DEG},${nearLon + BOX_DEG},${nearLat - BOX_DEG}",
                            )
                        }
                    }
                    .build()
                val request = Request.Builder().url(url)
                    .header("User-Agent", "BYDMate/${BuildConfig.VERSION_NAME} (car head unit app)")
                    .build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    parse(response.body?.string().orEmpty())
                }
            }
        }

    internal companion object {
        const val ENDPOINT = "https://nominatim.openstreetmap.org/search"
        const val LIMIT = 8
        /** About 100 km around the car: nearby places first, far ones still found. */
        const val BOX_DEG = 1.0

        fun parse(json: String): List<FoundPlace> {
            val arr = JSONArray(json)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                val full = o.optString("display_name")
                val name = o.optString("name").ifBlank { full.substringBefore(',') }
                FoundPlace(name, full, lat, lon)
            }
        }
    }
}
