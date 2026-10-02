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
import kotlinx.coroutines.CancellationException

class DiagnosticUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as T4LApplication
        val events = app.logger.pending()
        if (events.isEmpty()) return Result.success()
        val delivered = uploadDiagnosticEvents(events,
            send = { items -> app.apiClient.sendDiagnostics(items.map { item ->
                DiagnosticEventDto(item.timestamp, item.level, item.eventName, item.attributes,
                    app.repository.clientId, BuildConfig.VERSION_NAME)
            }) },
            acknowledge = app.logger::acknowledge,
            rejected = app.logger::recordRejectedUpload)
        return if (delivered) Result.success() else Result.retry()
    }
}

internal suspend fun uploadDiagnosticEvents(
    events: List<PendingDiagnosticEvent>,
    send: suspend (List<PendingDiagnosticEvent>) -> Unit,
    acknowledge: (List<PendingDiagnosticEvent>) -> Unit,
    rejected: (PendingDiagnosticEvent) -> Unit,
): Boolean {
    try {
        send(events)
        acknowledge(events)
        return true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (error !is HttpStatusException || error.statusCode != 400) return false
    }
    // A 400 response is atomic on the server; isolate only the permanently invalid item.
    for (event in events) {
        try {
            send(listOf(event))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error !is HttpStatusException || error.statusCode != 400) return false
            rejected(event)
        }
        acknowledge(listOf(event))
    }
    return true
}

object DiagnosticUploadScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<DiagnosticUploadWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork("t4l-diagnostic-upload", ExistingWorkPolicy.KEEP, request)
    }
}
