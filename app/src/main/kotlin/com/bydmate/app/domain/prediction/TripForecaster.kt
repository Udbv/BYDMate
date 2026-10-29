package com.bydmate.app.domain.prediction

import com.bydmate.app.data.local.dao.TripConditionsDao
import com.bydmate.app.data.local.dao.TripMinuteDao
import com.bydmate.app.data.local.dao.TripWeatherRow
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripMinuteEntity
import com.bydmate.app.data.repository.TripRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A drive's forecast next to what it really used. */
data class TripForecast(
    val conditions: TripConditions,
    /** Coldest to warmest minute and the height gained and lost; from the minutes when recorded. */
    val profile: TripProfile,
    /** Null until enough earlier drives exist, or for a drive too short to judge. */
    val prediction: TripPrediction?,
    val expectedKwh: Double?,
    val distanceKm: Double?,
    val actualKwh: Double?,
    val actualPer100: Double?,
    /** How the model has done on recent drives; null while there are too few. */
    val accuracy: ModelAccuracy?,
    /** Drives the forecast learned from. */
    val trainedOn: Int,
)

/**
 * Builds the trips' conditions (outside temperature, weather, speed) and the consumption
 * forecast. The forecast of a drive learns only from the drives before it, so an old trip shows
 * what the model would have said then, not a hindsight fit.
 */
@Singleton
class TripForecaster @Inject constructor(
    private val tripRepository: TripRepository,
    private val conditionsDao: TripConditionsDao,
    private val minuteDao: TripMinuteDao,
) {

    /** Conditions of [trips] by id; [since] bounds the minutes read to the trips' period. */
    suspend fun conditions(trips: List<TripEntity>, since: Long): Map<Long, TripConditions> =
        conditionsOf(trips, conditionsDao.tripWeather(since).associateBy { it.tripId })

    suspend fun forecast(trip: TripEntity): TripForecast {
        val trips = tripRepository.getAllTrips().first()
        val weather = conditionsDao.tripWeather(since = 0L).associateBy { it.tripId }
        val altitudes = conditionsDao.minuteAltitudes()
            .groupBy({ it.tripId }, { MinuteAltitude(it.minuteTs, it.altitude) })
            .mapValues { (_, list) -> list.sortedBy { it.minuteTs } }
        val minutes = minuteDao.getInRange(0L, Long.MAX_VALUE)
        val byId = trips.mapNotNull { t ->
            sampleOf(t, weather[t.id], minutesOf(t, minutes), altitudes[t.id].orEmpty())?.let { t.id to it }
        }.toMap()
        val samples = byId.values.toList()
        val before = samples.filter { it.startTs < trip.startTs }
        val model = ConsumptionModel(before)
        val own = byId[trip.id]
        val overall = own?.conditions ?: conditionsOf(trip, weather[trip.id])
        val prediction = own?.takeIf { model.size >= MIN_TRAINED && it.distanceKm >= MIN_TRAIN_KM }
            ?.let { model.predictTrip(it.segments) }
        return TripForecast(
            conditions = overall,
            profile = own?.let { TripSegments.profile(it.segments) } ?: TripProfile(overall.tempC, overall.tempC, 0.0, 0.0),
            prediction = prediction,
            expectedKwh = prediction?.kwh,
            distanceKm = trip.distanceKm,
            actualKwh = trip.kwhConsumed,
            actualPer100 = trip.kwhPer100km,
            accuracy = evaluate(samples),
            trainedOn = model.size,
        )
    }

    internal companion object {
        /** A forecast from fewer drives than this is a guess, not a forecast. */
        const val MIN_TRAINED = 5

        fun conditionsOf(trips: List<TripEntity>, rows: Map<Long, TripWeatherRow>): Map<Long, TripConditions> =
            trips.associate { it.id to conditionsOf(it, rows[it.id]) }

        fun conditionsOf(trip: TripEntity, row: TripWeatherRow?): TripConditions {
            val temp = row?.extTemp ?: endsTemperature(trip)
            return TripConditions(
                tempC = temp,
                weather = row?.let { tripWeather(it.wetMinutes, it.knownMinutes, temp) },
                avgSpeedKmh = trip.avgSpeedKmh ?: averageSpeed(trip),
            )
        }

        /**
         * A drive the model can learn from: an EV drive with its energy and distance known, cut
         * into minute segments where its minutes were recorded.
         */
        fun sampleOf(
            trip: TripEntity,
            weather: TripWeatherRow?,
            minutes: List<TripMinuteEntity>,
            altitudes: List<MinuteAltitude>,
        ): TripSample? {
            val km = trip.distanceKm ?: return null
            val kwh = trip.kwhConsumed ?: return null
            if (trip.endTs == null || km <= 0.0 || (trip.fuelLiters ?: 0.0) > 0.0) return null
            val net = altitudes.takeIf { it.size >= 2 }?.let { it.last().altitude - it.first().altitude }
            val overall = conditionsOf(trip, weather).copy(climbM = net)
            return TripSample(trip.startTs, km, kwh, overall, TripSegments.of(km, kwh, overall, minutes, altitudes))
        }

        /** The minutes of [trip] out of all minutes sorted by time; the first began before the trip did. */
        fun minutesOf(trip: TripEntity, all: List<TripMinuteEntity>): List<TripMinuteEntity> {
            val end = trip.endTs ?: return emptyList()
            val from = trip.startTs - MINUTE_MS + 1
            var i = all.binarySearchBy(from) { it.minuteTs }.let { if (it < 0) -it - 1 else it }
            val out = ArrayList<TripMinuteEntity>()
            while (i < all.size && all[i].minuteTs <= end) out += all[i++]
            return out
        }

        /** The mean of the two ends' outside temperature, or whichever end is known. */
        private fun endsTemperature(trip: TripEntity): Double? {
            val ends = listOfNotNull(trip.exteriorTemp, trip.exteriorTempEnd)
            return if (ends.isEmpty()) null else ends.average()
        }

        private fun averageSpeed(trip: TripEntity): Double? {
            val km = trip.distanceKm ?: return null
            val end = trip.endTs ?: return null
            val hours = (end - trip.startTs) / MS_PER_HOUR
            return if (hours > 0 && km > 0) km / hours else null
        }

        private const val MS_PER_HOUR = 3_600_000.0
        private const val MINUTE_MS = 60_000L
    }
}
