package app.t4l

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.t4l.data.SyncWorker
import java.util.concurrent.TimeUnit

class SyncScheduler(
    context: Context,
    private val settings: ServerSettings,
) {
    private val workManager = WorkManager.getInstance(context)
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun scheduleColdStart() = syncNow()

    fun cancelObsoleteWork() {
        workManager.cancelUniqueWork(LEGACY_PERIODIC_WORK)
        workManager.cancelUniqueWork(LEGACY_IMMEDIATE_WORK)
    }

    fun scheduleAfterChange() {
        val delay = settings.syncDelay.seconds
        if (delay == 0L) {
            syncNow()
            return
        }
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delay, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(DEBOUNCE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun syncNow() {
        workManager.cancelUniqueWork(DEBOUNCE_WORK)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private companion object {
        const val DEBOUNCE_WORK = "t4l-sync-debounce"
        const val IMMEDIATE_WORK = "t4l-sync-immediate"
        const val LEGACY_PERIODIC_WORK = "t4l-sync"
        const val LEGACY_IMMEDIATE_WORK = "t4l-sync-now"
    }
}
