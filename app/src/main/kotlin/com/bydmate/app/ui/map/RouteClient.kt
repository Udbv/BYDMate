package com.bydmate.app.ui.map

import com.bydmate.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** A driving route: its road length, time and shape. */
data class RoutePlan(val distanceKm: Double, val durationMin: Double, val points: List<LatLon>)

/**
 * Car routes from the OSRM server the openstreetmap.org site itself uses (FOSSGIS): one request
 * per picked target, with a real User-Agent as its fair-use policy asks.
 */
@Singleton
class RouteClient @Inject constructor(private val http: OkHttpClient) {

    suspend fun route(from: LatLon, to: LatLon): Result<RoutePlan> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$ENDPOINT/${from.lon},${from.lat};${to.lon},${to.lat}?overview=full&geometries=geojson"
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
        const val ENDPOINT = "https://routing.openstreetmap.de/routed-car/route/v1/driving"
        private const val M_PER_KM = 1000.0
        private const val S_PER_MIN = 60.0

        fun parse(json: String): RoutePlan {
            val root = JSONObject(json)
            check(root.optString("code") == "Ok") { "route: ${root.optString("code")}" }
            val route = root.getJSONArray("routes").getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = (0 until coords.length()).map { i ->
                val c = coords.getJSONArray(i)
                LatLon(c.getDouble(1), c.getDouble(0))
            }
            return RoutePlan(route.getDouble("distance") / M_PER_KM, route.getDouble("duration") / S_PER_MIN, points)
        }
    }
}
