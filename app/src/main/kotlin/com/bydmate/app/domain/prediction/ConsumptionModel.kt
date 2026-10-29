package com.bydmate.app.domain.prediction

import kotlin.math.abs
import kotlin.math.floor

/** What the sky did on a drive, read from the wipers and the outside temperature. */
enum class TripWeather { DRY, RAIN, SNOW }

/**
 * The conditions a drive's consumption depends on that the driver does not choose: outside
 * temperature, weather, how fast the traffic let the car go and the height gained from start to
 * finish ([climbM], negative downhill). Any of them can be unknown (old trips, a car that does not
 * report the wipers, no GPS height).
 */
data class TripConditions(
    val tempC: Double?,
    val weather: TripWeather?,
    val avgSpeedKmh: Double?,
    val climbM: Double? = null,
) {
    internal val tempBin: Int? get() = tempC?.let { floor(it / TEMP_BIN_C).toInt().coerceIn(MIN_TEMP_BIN, MAX_TEMP_BIN) }
    internal val speedClass: SpeedClass? get() = avgSpeedKmh?.let { SpeedClass.of(it) }
}

enum class SpeedClass {
    CITY, MIXED, HIGHWAY;

    companion object {
        fun of(avgKmh: Double): SpeedClass = when {
            avgKmh < CITY_MAX_KMH -> CITY
            avgKmh <= MIXED_MAX_KMH -> MIXED
            else -> HIGHWAY
        }
    }
}

/**
 * Weather of a drive from its minutes: wet when the wipers ran in at least [WET_MINUTE_SHARE] of
 * the minutes the car reported them (one wash of the windscreen stays dry), snow when wet at or
 * below [SNOW_MAX_C]. Null when no minute knew the wipers.
 */
fun tripWeather(wetMinutes: Int, knownMinutes: Int, tempC: Double?): TripWeather? = when {
    knownMinutes <= 0 -> null
    wetMinutes.toDouble() / knownMinutes < WET_MINUTE_SHARE -> TripWeather.DRY
    tempC != null && tempC <= SNOW_MAX_C -> TripWeather.SNOW
    else -> TripWeather.RAIN
}

/**
 * Energy the height change of a drive costs, kWh: lifting the car takes m·g·h from the battery
 * through the drive's losses; coming down gives part of it back through regen (negative). A net
 * change under [MIN_CLIMB_M] is GPS noise and costs nothing. Only the net change counts: a hill
 * on the way that the drive comes back down from is mostly regen, not lost.
 */
fun climbKwh(climbM: Double?): Double {
    if (climbM == null || abs(climbM) < MIN_CLIMB_M) return 0.0
    val potential = CAR_MASS_KG * GRAVITY * climbM / JOULES_PER_KWH
    return if (climbM > 0) potential / DRIVE_EFFICIENCY else potential * REGEN_EFFICIENCY
}

/** A finished drive the model learns from. */
data class TripSample(
    val startTs: Long,
    val distanceKm: Double,
    val kwh: Double,
    val conditions: TripConditions,
) {
    val per100: Double get() = kwh / distanceKm * PER_KM

    /** What the drive would have used on the flat: the model learns this, the height is physics. */
    internal val flatKwh: Double get() = kwh - climbKwh(conditions.climbM)
    internal val usable: Boolean get() = distanceKm >= MIN_TRAIN_KM && per100 in MIN_PER100..MAX_PER100
}

/** Which group answered: the deeper, the more of the conditions it matched. */
enum class PredictionLevel { ALL, TEMP, TEMP_SPEED, FULL }

/**
 * The expected consumption on the flat, kWh/100 km, and how many past drives the deepest group
 * held. A drive's own height change is added on top by [kwhFor].
 */
data class Prediction(val per100: Double, val level: PredictionLevel, val trips: Int) {
    fun kwhFor(distanceKm: Double, climbM: Double? = null): Double = per100 * distanceKm / PER_KM + climbKwh(climbM)
}

/**
 * Expected consumption from past drives in similar conditions. Groups narrow from all drives to
 * the same temperature band, then the same speed class, then the same weather; each group's
 * average leans on the wider one until it holds enough drives ([SHRINK_TRIPS] act as the wider
 * group's weight), so a band with two drives does not swing the forecast. Averages weigh drives
 * by distance: a 40 km drive says more than a 3 km one.
 */
class ConsumptionModel(history: List<TripSample>) {

    private val trained = history.filter { it.usable }
    private val all = Cell.of(trained)
    private val byTemp = trained.groupBy { it.conditions.tempBin }.mapValues { Cell.of(it.value) }
    private val byTempSpeed = trained.groupBy { it.conditions.tempBin to it.conditions.speedClass }
        .mapValues { Cell.of(it.value) }
    private val byFull = trained.groupBy { Triple(it.conditions.tempBin, it.conditions.speedClass, it.conditions.weather) }
        .mapValues { Cell.of(it.value) }

    val size: Int get() = trained.size

    fun predict(c: TripConditions): Prediction? {
        val base = all.rate ?: return null
        var est = base
        var level = PredictionLevel.ALL
        var trips = all.trips
        val tempBin = c.tempBin ?: return Prediction(est, level, trips)
        byTemp[tempBin]?.let { cell ->
            est = cell.shrunkTo(est)
            level = PredictionLevel.TEMP
            trips = cell.trips
        }
        val speed = c.speedClass ?: return Prediction(est, level, trips)
        byTempSpeed[tempBin to speed]?.let { cell ->
            est = cell.shrunkTo(est)
            level = PredictionLevel.TEMP_SPEED
            trips = cell.trips
        }
        val weather = c.weather ?: return Prediction(est, level, trips)
        byFull[Triple(tempBin, speed, weather)]?.let { cell ->
            est = cell.shrunkTo(est)
            level = PredictionLevel.FULL
            trips = cell.trips
        }
        return Prediction(est, level, trips)
    }

    private class Cell(val kwh: Double, val km: Double, val trips: Int) {
        val rate: Double? get() = if (km > 0) kwh / km * PER_KM else null

        /** This group's rate pulled toward [parent] while the group is small. */
        fun shrunkTo(parent: Double): Double {
            val own = rate ?: return parent
            return (trips * own + SHRINK_TRIPS * parent) / (trips + SHRINK_TRIPS)
        }

        companion object {
            fun of(samples: List<TripSample>) = Cell(samples.sumOf { it.flatKwh }, samples.sumOf { it.distanceKm }, samples.size)
        }
    }
}

/** How the forecasts did on recent drives, each forecast made only from the drives before it. */
data class ModelAccuracy(
    val trips: Int,
    /** Mean absolute error, kWh/100 km. */
    val maePer100: Double,
    /** Mean of actual − expected: above 0 the model is too optimistic. */
    val biasPer100: Double,
    /** The same error for a forecast that ignores the conditions and the height (the plain average), to beat. */
    val baselineMaePer100: Double,
)

/**
 * Walk-forward check of the model on the last [window] usable drives: each is forecast from the
 * drives that started before it, exactly as the trip page did when that drive was new. Null
 * until [MIN_EVAL_TRIPS] drives could be checked.
 */
fun evaluate(history: List<TripSample>, window: Int = EVAL_WINDOW): ModelAccuracy? {
    val sorted = history.filter { it.usable }.sortedBy { it.startTs }
    val errors = sorted.takeLast(window).mapNotNull { target ->
        val before = sorted.filter { it.startTs < target.startTs }
        if (before.size < MIN_HISTORY) return@mapNotNull null
        val model = ConsumptionModel(before).predict(target.conditions) ?: return@mapNotNull null
        val expected = model.kwhFor(target.distanceKm, target.conditions.climbM) / target.distanceKm * PER_KM
        val plain = before.sumOf { it.kwh } / before.sumOf { it.distanceKm } * PER_KM
        Triple(target.per100 - expected, abs(target.per100 - expected), abs(target.per100 - plain))
    }
    if (errors.size < MIN_EVAL_TRIPS) return null
    return ModelAccuracy(
        trips = errors.size,
        maePer100 = errors.map { it.second }.average(),
        biasPer100 = errors.map { it.first }.average(),
        baselineMaePer100 = errors.map { it.third }.average(),
    )
}

private const val PER_KM = 100.0
private const val TEMP_BIN_C = 10.0
private const val MIN_TEMP_BIN = -2
private const val MAX_TEMP_BIN = 3
private const val CITY_MAX_KMH = 40.0
private const val MIXED_MAX_KMH = 70.0
private const val WET_MINUTE_SHARE = 0.3
private const val SNOW_MAX_C = 1.0

/** Shorter drives are mostly the warm-up and the climate starting: too noisy to learn from. */
const val MIN_TRAIN_KM = 3.0
private const val MIN_PER100 = 5.0
private const val MAX_PER100 = 60.0
private const val SHRINK_TRIPS = 3.0
private const val EVAL_WINDOW = 30

/** A Tang L with a driver and some luggage; a few hundred kg either way moves a 100 m climb by ~0.1 kWh. */
private const val CAR_MASS_KG = 2_750.0
private const val GRAVITY = 9.81
private const val JOULES_PER_KWH = 3_600_000.0
private const val DRIVE_EFFICIENCY = 0.88
private const val REGEN_EFFICIENCY = 0.65
private const val MIN_CLIMB_M = 10.0
private const val MIN_HISTORY = 5
private const val MIN_EVAL_TRIPS = 5
