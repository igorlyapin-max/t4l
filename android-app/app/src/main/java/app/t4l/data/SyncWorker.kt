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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as T4LApplication
        val startedAt = System.currentTimeMillis()
        app.syncStateStore.syncing()
        app.logger.verboseEvent("sync_started", mapOf("phase" to "sync"))
        return runCatching { sync(app) }.fold(
            { app.syncStateStore.success(); app.logger.event("sync_succeeded", mapOf("durationMs" to (System.currentTimeMillis() - startedAt).toString())); Result.success() },
            { error -> app.syncStateStore.retry(error.javaClass.simpleName); app.logger.event("sync_retry", mapOf("errorType" to error.javaClass.simpleName)); Result.retry() },
        )
    }

    private suspend fun sync(app: T4LApplication) {
        val bootstrap = app.apiClient.bootstrap()
        app.repository.updateBootstrap(bootstrap.userId, bootstrap.defaultWorkspaceId,
            bootstrap.workspaces.map { WorkspaceOption(it.workspaceId, it.name, it.role) })
        val removedPersonalItems = app.profileRepository.bindUser(bootstrap.userId)
        if (removedPersonalItems > 0) app.logger.event("profile_actor_rebound", mapOf("pendingCount" to removedPersonalItems.toString()))
        app.profileRepository.sync(bootstrap.userId)
        val dao = app.database.dao()
        while (!dao.hasUnresolvedAtomicConflict(app.repository.workspaceId)) {
            val first = dao.pendingMutations(app.repository.workspaceId, 1).firstOrNull() ?: break
            if (first.attemptCount > 0) break
            val pending = first.atomicGroupId?.let { dao.pendingAtomicGroup(it) } ?: listOf(first)
            if (pending.size !in 1..2 || pending.any { it.workspaceId != first.workspaceId }) {
                dao.markMutationFailure(pending.map { it.clientMutationId }, "invalid_atomic_group")
                break
            }
            val response = app.apiClient.push(PushBody(app.repository.clientId, pending.map { it.toMutation() }))
            val results = response.results.associateBy { it.clientMutationId }
            if (pending.all { results[it.clientMutationId]?.status == "applied" }) {
                app.database.withTransaction { dao.deleteMutations(pending.map { it.clientMutationId }) }
                continue
            }
            val conflicted = pending.firstOrNull { results[it.clientMutationId]?.status == "conflict" && results[it.clientMutationId]?.serverEntity != null }
            if (conflicted != null) {
                val result = requireNotNull(results[conflicted.clientMutationId])
                app.database.withTransaction {
                    dao.putConflict(ConflictRow(result.clientMutationId, conflicted.workspaceId, conflicted.entityType, result.canonicalEntityId ?: conflicted.entityId,
                        conflicted.payloadJson, result.serverEntity?.toString().orEmpty(), result.revision ?: 0,
                        System.currentTimeMillis(), conflicted.operation,
                        if (first.atomicGroupId != null) "atomic_group_${result.errorCode ?: "conflict"}" else result.errorCode,
                        pending.firstOrNull { it.entityType.equals("event", true) }?.entityId))
                    dao.deleteMutations(pending.map { it.clientMutationId })
                }
            } else {
                dao.markMutationFailure(pending.map { it.clientMutationId }, response.results.firstOrNull { it.status != "applied" }?.errorCode ?: "atomic_group_aborted")
            }
            break
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
        val invalidPlans = dao.invalidPlans(workspaceId).filter {
            !dao.hasPendingForEntity("plan", it.id) && !dao.hasConflictForEntity("plan", it.id)
        }
        if (invalidPlans.isNotEmpty()) {
            val snapshot = Json.parseToJsonElement(app.apiClient.snapshot(workspaceId)).jsonObject
            val canonical = snapshot["plans"]?.jsonArray?.mapNotNull { value ->
                val plan = value.jsonObject
                plan["id"]?.jsonPrimitive?.contentOrNull?.let { it to plan }
            }?.toMap().orEmpty()
            app.database.withTransaction {
                invalidPlans.forEach { local ->
                    val payload = canonical[local.id] ?: return@forEach
                    val revision = payload["revision"]?.jsonPrimitive?.longOrNull ?: return@forEach
                    runCatching {
                        applyApiChange(dao, workspaceId, ApiChange(0, "plan", local.id, revision, false, payload))
                    }.onFailure { app.logger.event("plan_period_repair_skipped", mapOf("errorType" to it.javaClass.simpleName)) }
                }
            }
        }
    }

    private fun OutboxRow.toMutation() = ApiMutation(clientMutationId, workspaceId, entityType, entityId, operation, baseRevision, Json.parseToJsonElement(payloadJson) as JsonObject, atomicGroupId)
}
