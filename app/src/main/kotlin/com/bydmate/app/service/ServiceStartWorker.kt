package com.bydmate.app.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.bydmate.app.R
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/**
 * WorkManager worker that starts TrackingService.
 *
 * Used by BootReceiver instead of direct startForegroundService().
 * WorkManager guarantees execution even after process death —
 * same approach as BydConnect (ServiceStartWorker).
 */
class ServiceStartWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ServiceStartWorker"
        const val WORK_NAME = "ServiceStart"
        private const val BOOT_NOTIFICATION_ID = 4711
    }


    /**
     * Required for expedited work below API 31, where WorkManager runs the request as a short
     * foreground job. Uses the same quiet channel the tracking notification can use, so nothing
     * new appears in the shade during the few seconds this takes.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = TrackingService.bootWorkerChannelId(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(BOOT_NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(BOOT_NOTIFICATION_ID, notification)
        }
    }
    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting TrackingService via WorkManager")
        ChainLog.append(applicationContext, "Worker doWork started")
        return try {
            val intent = Intent(applicationContext, TrackingService::class.java).apply {
                putExtra("onBoot", true)
            }
            ContextCompat.startForegroundService(applicationContext, intent)
            ChainLog.append(applicationContext, "startForegroundService OK")
            Log.i(TAG, "startForegroundService OK")
            Result.success()
        } catch (e: Exception) {
            ChainLog.append(applicationContext, "startForegroundService FAILED: ${e.message}")
            Log.e(TAG, "Failed to start TrackingService: ${e.message}", e)
            Result.retry()
        }
    }
}
