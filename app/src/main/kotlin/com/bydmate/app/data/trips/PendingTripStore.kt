package com.bydmate.app.data.trips

import android.content.SharedPreferences
import com.bydmate.app.data.local.entity.TripEntity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Shadow trips of a passive device (2026-11-14 missing trips): TripRecorder's own trips, recorded
 * while the energydata file is trusted to bring them, kept here until the next import says whether
 * BYD recorded the drive ([ShadowTripResolver]). Keyed by the trip's start.
 */
interface PendingTripStore {
    fun all(): List<TripEntity>
    fun add(trip: TripEntity)
    fun remove(startTs: Long)
}

/** [PendingTripStore] in its own prefs file, outside the backup: it is only meaningful on this car. */
class PendingTripStorePrefs(private val prefs: SharedPreferences) : PendingTripStore {

    private val lock = Any()

    override fun all(): List<TripEntity> = synchronized(lock) { load() }

    override fun add(trip: TripEntity) = synchronized(lock) {
        val kept = load().filterNot { it.startTs == trip.startTs } + trip
        save(kept.sortedBy { it.startTs }.takeLast(MAX_PENDING))
    }

    override fun remove(startTs: Long) = synchronized(lock) {
        save(load().filterNot { it.startTs == startTs })
    }

    private fun load(): List<TripEntity> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    private fun save(trips: List<TripEntity>) {
        val array = JSONArray()
        trips.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "pending_shadow_trips"
        private const val KEY = "trips"

        /** A device whose imports keep failing must not grow this list forever. */
        const val MAX_PENDING = 30

        private val DOUBLE_KEYS = listOf(
            "distanceKm", "kwhConsumed", "kwhPer100km", "odometerStartKm", "odometerEndKm",
            "fuelLiters", "hevKm", "fuelTotalStart", "fuelTotalEnd", "mileageHevStart", "mileageHevEnd",
        )

        internal fun toJson(t: TripEntity): JSONObject {
            val o = JSONObject().put("startTs", t.startTs).put("source", t.source)
            t.endTs?.let { o.put("endTs", it) }
            t.socStart?.let { o.put("socStart", it) }
            t.socEnd?.let { o.put("socEnd", it) }
            t.exteriorTemp?.let { o.put("exteriorTemp", it) }
            t.exteriorTempEnd?.let { o.put("exteriorTempEnd", it) }
            val doubles = listOf(
                t.distanceKm, t.kwhConsumed, t.kwhPer100km, t.odometerStartKm, t.odometerEndKm,
                t.fuelLiters, t.hevKm, t.fuelTotalStart, t.fuelTotalEnd, t.mileageHevStart, t.mileageHevEnd,
            )
            DOUBLE_KEYS.zip(doubles).forEach { (key, value) -> value?.let { o.put(key, it) } }
            return o
        }

        internal fun fromJson(o: JSONObject): TripEntity {
            fun d(key: String): Double? = if (o.has(key)) o.getDouble(key) else null
            fun i(key: String): Int? = if (o.has(key)) o.getInt(key) else null
            return TripEntity(
                startTs = o.getLong("startTs"),
                endTs = if (o.has("endTs")) o.getLong("endTs") else null,
                distanceKm = d("distanceKm"),
                kwhConsumed = d("kwhConsumed"),
                kwhPer100km = d("kwhPer100km"),
                socStart = i("socStart"),
                socEnd = i("socEnd"),
                exteriorTemp = i("exteriorTemp"),
                exteriorTempEnd = i("exteriorTempEnd"),
                odometerStartKm = d("odometerStartKm"),
                odometerEndKm = d("odometerEndKm"),
                source = o.optString("source", TripSource.NATIVE_FALLBACK),
                fuelLiters = d("fuelLiters"),
                hevKm = d("hevKm"),
                fuelTotalStart = d("fuelTotalStart"),
                fuelTotalEnd = d("fuelTotalEnd"),
                mileageHevStart = d("mileageHevStart"),
                mileageHevEnd = d("mileageHevEnd"),
            )
        }
    }
}
