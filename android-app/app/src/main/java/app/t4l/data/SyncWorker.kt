package app.t4l.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.t4l.T4LApplication
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as T4LApplication
        app.syncStateStore.syncing()
        return runCatching { sync(app) }.fold(
            { app.syncStateStore.success(); app.logger.event("sync_succeeded"); Result.success() },
            { error -> app.syncStateStore.retry(error.javaClass.simpleName); app.logger.event("sync_retry", mapOf("errorType" to error.javaClass.simpleName)); Result.retry() },
        )
    }

    private suspend fun sync(app: T4LApplication) {
        val bootstrap = app.apiClient.bootstrap()
        app.repository.updateBootstrap(bootstrap.userId, bootstrap.defaultWorkspaceId,
            bootstrap.workspaces.map { WorkspaceOption(it.workspaceId, it.name, it.role) })
        val dao = app.database.dao(); val pending = dao.pendingMutations(app.repository.workspaceId)
        if (pending.isNotEmpty()) {
            val response = app.apiClient.push(PushBody(app.repository.clientId, pending.map { it.toMutation() }))
            app.database.withTransaction {
                response.results.forEach { result ->
                    val local = pending.single { it.clientMutationId == result.clientMutationId }
                    when (result.status) {
                        "applied" -> dao.deleteMutations(listOf(result.clientMutationId))
                        "conflict" -> {
                            dao.putConflict(ConflictRow(result.clientMutationId, local.workspaceId, local.entityType, result.canonicalEntityId ?: local.entityId,
                                local.payloadJson, result.serverEntity?.toString().orEmpty(), result.revision ?: 0,
                                System.currentTimeMillis(), local.operation, result.errorCode))
                            dao.deleteMutations(listOf(result.clientMutationId))
                        }
                        else -> dao.markMutationFailure(listOf(result.clientMutationId), result.errorCode ?: "rejected")
                    }
                }
            }
        }
        val workspaceId = app.repository.workspaceId; var cursor = dao.cursor(workspaceId)?.cursor ?: 0
        do {
            val page = app.apiClient.changes(workspaceId, cursor)
            app.database.withTransaction {
                page.changes.forEach {
                    if (!dao.hasPendingForEntity(it.entityType, it.entityId) && !dao.hasConflictForEntity(it.entityType, it.entityId)) {
                        applyApiChange(dao, workspaceId, it)
                    }
                }
                dao.putCursor(SyncCursorRow(workspaceId, page.nextCursor))
            }
            cursor = page.nextCursor
        } while (page.hasMore)
    }

    private fun OutboxRow.toMutation() = ApiMutation(clientMutationId, workspaceId, entityType, entityId, operation, baseRevision, Json.parseToJsonElement(payloadJson) as JsonObject)
}
