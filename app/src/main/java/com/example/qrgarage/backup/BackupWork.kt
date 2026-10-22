package com.example.qrgarage.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.qrgarage.MainActivity
import com.example.qrgarage.R
import com.example.qrgarage.data.settings.BackupSchedule
import com.example.qrgarage.data.settings.BackupSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** BK-5: backups run in a CoroutineWorker with exponential backoff. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backups: BackupManager,
    private val notifier: BackupNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val scheduled = inputData.getBoolean(KEY_SCHEDULED, false)
        return try {
            backups.backUpNow()
            Result.success()
        } catch (e: AuthRequiredException) {
            if (scheduled) notifier.notifyFailure("Reconnect Google Drive in Settings → Backup to resume backups.")
            Result.failure(workDataOf(KEY_ERROR to e.message))
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                // SET-BK-7: tell the user when a scheduled backup gives up.
                if (scheduled) notifier.notifyFailure(e.message ?: "Backup failed")
                Result.failure(workDataOf(KEY_ERROR to e.message))
            }
        }
    }

    companion object {
        const val KEY_SCHEDULED = "scheduled"
        const val KEY_ERROR = "error"
        const val MAX_ATTEMPTS = 3
    }
}

@Singleton
class BackupScheduler @Inject constructor(private val workManager: WorkManager) {

    /** SET-BK-2: "Back up now" also goes through WorkManager so it survives leaving the screen. */
    fun backUpNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_MANUAL)
            .build()
        workManager.enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, request)
    }

    val manualRunning: Flow<Boolean> = workManager.getWorkInfosForUniqueWorkFlow(MANUAL).map { infos ->
        infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }

    /** SET-BK-3: off / daily / weekly with Wi-Fi-only and charging-only constraints. */
    fun apply(cfg: BackupSettings) {
        if (cfg.schedule == BackupSchedule.OFF || !cfg.connected) {
            workManager.cancelUniqueWork(PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (cfg.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(cfg.chargingOnly)
            .setRequiresBatteryNotLow(true)
            .build()
        val days = if (cfg.schedule == BackupSchedule.DAILY) 1L else 7L
        val request = PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(workDataOf(BackupWorker.KEY_SCHEDULED to true))
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val MANUAL = "backup-now"
        const val PERIODIC = "backup-periodic"
        const val TAG_MANUAL = "backup-manual"
    }
}

@Singleton
class BackupNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun notifyFailure(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel()
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Garage backup failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_backup), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notif_channel_backup_desc) }
        )
    }

    private companion object {
        const val CHANNEL = "backup"
        const val NOTIFICATION_ID = 1001
    }
}
