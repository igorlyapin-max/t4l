package app.t4l

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.t4l.data.DiagnosticEventDto
import app.t4l.data.HttpStatusException

class DiagnosticUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as T4LApplication
        val events = app.logger.pending()
        if (events.isEmpty()) return Result.success()
        return runCatching {
            app.apiClient.sendDiagnostics(events.map { item ->
                DiagnosticEventDto(item.timestamp, item.level, item.eventName, item.attributes,
                    app.repository.clientId, BuildConfig.VERSION_NAME)
            })
            app.logger.acknowledge(events.size)
            Result.success()
        }.getOrElse { error ->
            if (error is HttpStatusException && error.statusCode == 400) {
                app.logger.acknowledge(events.size)
                Result.failure()
            } else Result.retry()
        }
    }
}

object DiagnosticUploadScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<DiagnosticUploadWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork("t4l-diagnostic-upload", ExistingWorkPolicy.KEEP, request)
    }
}
