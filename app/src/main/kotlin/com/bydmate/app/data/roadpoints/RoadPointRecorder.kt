package com.bydmate.app.data.roadpoints

import android.content.Context
import android.location.Location
import android.util.Log
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.local.dao.RoadPointDao
import com.bydmate.app.data.local.entity.RoadPointEntity
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.diagnostics.TripDebugLog
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What a mark did: a new point, one more pass of a known one, or nothing for want of a position. */
sealed interface MarkResult {
    data class Added(val type: RoadPointType) : MarkResult
    data class Passed(val type: RoadPointType, val passes: Int) : MarkResult
    data object NoPosition : MarkResult
}

/**
 * «Мої точки» (#22): marks the car's position as a [RoadPointType] (by voice: «лежачий тут»), lists
 * the points and exports them to the owner's Telegram bot for Waze's map editors.
 */
@Singleton
class RoadPointRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: RoadPointDao,
    private val sink: TelegramBackupSink,
    private val settings: SettingsRepository,
) {
    internal var location: () -> Location? = { TrackingService.lastLocation.value }
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    fun observe(): Flow<List<RoadPointEntity>> = dao.observeAll()

    suspend fun mark(type: RoadPointType): MarkResult =
        markAt(type, location()?.takeIf { nowMs() - it.time <= MAX_FIX_AGE_MS })

    /**
     * Marks [type] at [at]: the fix the caller chose (the car's position now, or where a confirmed
     * bump was felt seconds ago, #22). Nothing without a fix as precise as [MAX_ACCURACY_M].
     */
    suspend fun markAt(type: RoadPointType, at: Location?): MarkResult {
        val loc = at?.takeIf { it.accuracy <= MAX_ACCURACY_M } ?: return MarkResult.NoPosition
        val heading = loc.bearing.toDouble().takeIf { loc.hasBearing() && loc.hasSpeed() && loc.speed >= MIN_SPEED_MPS }
        val box = RoadPoints.box(loc.latitude, loc.longitude)
        val near = dao.inBox(type.name, box[0], box[1], box[2], box[3])
        val now = nowMs()
        val target = RoadPoints.mergeTarget(loc.latitude, loc.longitude, heading, near)
        val result = if (target != null) {
            val passes = target.passes + 1
            dao.update(target.copy(passes = passes, lastTs = now, heading = target.heading ?: heading))
            MarkResult.Passed(type, passes)
        } else {
            dao.insert(
                RoadPointEntity(
                    ts = now, lastTs = now, lat = loc.latitude, lon = loc.longitude, heading = heading,
                    type = type.name, passes = 1, status = RoadPointStatus.CANDIDATE.name,
                ),
            )
            MarkResult.Added(type)
        }
        // The trip log says that a point was marked, never where.
        TripDebugLog.event("POINT", "marked ${type.id} -> $result accuracy=${loc.accuracy.toInt()}m")
        Log.i(TAG, "marked ${type.id}: $result")
        return result
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** The list and the GeoJSON to the bot; null on success, otherwise why not. */
    suspend fun exportToTelegram(): String? {
        val config = settings.getTgBackupConfig()
        val chatId = config.chatId
        if (!config.configured || chatId == null) return "Telegram-бот не підключено"
        val points = dao.all()
        if (RoadPointExport.exportable(points).isEmpty()) return "Немає точок для експорту"
        val dir = File(context.cacheDir, "road-points").apply { mkdirs() }
        val text = File(dir, "bydmate-points.txt")
        val json = File(dir, "bydmate-points.geojson")
        withContext(Dispatchers.IO) {
            text.writeText(RoadPointExport.text(points))
            json.writeText(RoadPointExport.geoJson(points))
        }
        return try {
            val caption = "Мої точки для Waze: ${RoadPointExport.exportable(points).size}"
            val sent = sink.sendDocument(config.token, chatId, text, caption)
                .let { if (it.isSuccess) sink.sendDocument(config.token, chatId, json, "GeoJSON для WME") else it }
            sent.exceptionOrNull()?.let { e ->
                Log.w(TAG, "export failed: ${e.javaClass.simpleName}")
                "Не вдалося надіслати: ${e.javaClass.simpleName}"
            }
        } finally {
            withContext(Dispatchers.IO) {
                text.delete()
                json.delete()
            }
        }
    }

    companion object {
        private const val TAG = "RoadPoints"
        /** A fix older than this is not where the car is. */
        const val MAX_FIX_AGE_MS = 10_000L
        /** A fix worse than this misses a speed bump by more than a car length or two. */
        const val MAX_ACCURACY_M = 25f
        /** Below this the GPS course is noise. */
        const val MIN_SPEED_MPS = 2f
    }
}
