package com.bydmate.app.domain.prediction

import com.bydmate.app.data.local.dao.TripConditionsDao
import com.bydmate.app.data.local.dao.TripWeatherRow
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.TripRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A drive's forecast next to what it really used. */
data class TripForecast(
    val conditions: TripConditions,
    /** Null until enough earlier drives exist, or for a drive too short to judge. */
    val prediction: Prediction?,
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
) {

    /** Conditions of [trips] by id; [since] bounds the minutes read to the trips' period. */
    suspend fun conditions(trips: List<TripEntity>, since: Long): Map<Long, TripConditions> =
        conditionsOf(trips, conditionsDao.tripWeather(since).associateBy { it.tripId })

    suspend fun forecast(trip: TripEntity): TripForecast {
        val trips = tripRepository.getAllTrips().first()
        val climbs = conditionsDao.endsAltitude().mapNotNull { ends ->
            val start = ends.startAlt ?: return@mapNotNull null
            val end = ends.endAlt ?: return@mapNotNull null
            ends.tripId to end - start
        }.toMap()
        val conditions = conditionsOf(trips, conditionsDao.tripWeather(since = 0L).associateBy { it.tripId })
            .mapValues { (id, c) -> c.copy(climbM = climbs[id]) }
        val samples = trips.mapNotNull { sampleOf(it, conditions[it.id]) }
        val before = samples.filter { it.startTs < trip.startTs }
        val model = ConsumptionModel(before)
        val own = conditions[trip.id] ?: conditionsOf(trip, null).copy(climbM = climbs[trip.id])
        val distance = trip.distanceKm
        val prediction = if (model.size >= MIN_TRAINED && distance != null && distance >= MIN_TRAIN_KM) {
            model.predict(own)
        } else {
            null
        }
        return TripForecast(
            conditions = own,
            prediction = prediction,
            expectedKwh = distance?.let { prediction?.kwhFor(it, own.climbM) },
            distanceKm = distance,
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

        /** A drive the model can learn from: an EV drive with its energy and distance known. */
        fun sampleOf(trip: TripEntity, conditions: TripConditions?): TripSample? {
            val km = trip.distanceKm ?: return null
            val kwh = trip.kwhConsumed ?: return null
            if (trip.endTs == null || km <= 0.0 || (trip.fuelLiters ?: 0.0) > 0.0) return null
            return TripSample(trip.startTs, km, kwh, conditions ?: conditionsOf(trip, null))
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
    }
}
