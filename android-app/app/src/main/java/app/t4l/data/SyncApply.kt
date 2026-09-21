package app.t4l.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun applyApiChange(dao: T4LDao, defaultWorkspace: String, change: ApiChange) {
    if (change.deleted) {
        deleteLocalEntity(dao, change.entityType, change.entityId)
        return
    }
    val p = change.payload
    val workspace = p.text("workspaceId") ?: defaultWorkspace
    val updated = p.instant("updatedAt") ?: System.currentTimeMillis()
    val deleted = p.instant("deletedAt")
    when (change.entityType.lowercase()) {
        "categorytree" -> dao.putCategoryTree(CategoryTreeRow(change.entityId, workspace, p.text("name").orEmpty(), p.text("role") ?: "standard", p.int("sortOrder"), p.bool("archived"), change.revision, updated, deleted, LocalSyncState.SYNCED))
        "category" -> dao.putCategories(listOf(CategoryRow(change.entityId, workspace, p.text("categoryTreeId").orEmpty(), p.text("parentId"), p.text("name").orEmpty(), p.text("loadType") ?: "light", p.int("sortOrder"), p.bool("archived"), change.revision, updated, deleted, LocalSyncState.SYNCED)))
        "event" -> dao.putEvent(EventRow(change.entityId, workspace, p.text("categoryTreeId").orEmpty(), p.text("categoryId"), p.text("taskId"), p.instant("occurredAt") ?: 0, p.text("zoneId") ?: "UTC", p.text("source") ?: "manual", p.text("note"), change.revision, updated, deleted, LocalSyncState.SYNCED))
        "task" -> dao.putTask(TaskRow(change.entityId, workspace, p.text("title").orEmpty(), p.text("categoryId"), p.text("parentTaskId"), p.int("estimateMinutes"), p.int("remainingEstimateMinutes"), p.instant("deadline"), p.text("nextActionDate")?.let(LocalDate::parse)?.toEpochDay(), p.text("nextActionTime")?.let(LocalTime::parse)?.let { it.hour * 60 + it.minute }, p.text("zoneId") ?: "UTC", p.int("value"), p.text("energy") ?: "medium", p.int("progress"), p.text("status") ?: "active", p.bool("splittable"), change.revision, updated, deleted, LocalSyncState.SYNCED))
        "taskcomment" -> dao.putTaskComment(TaskCommentRow(change.entityId, workspace, p.text("taskId").orEmpty(), p.text("authorId").orEmpty(), p.text("text").orEmpty(), change.revision, updated, deleted, LocalSyncState.SYNCED))
        "plan" -> dao.putPlan(PlanRow(change.entityId, workspace, p.text("name").orEmpty(), p.text("kind") ?: "timeline", p.instant("startsAt") ?: 0, p.instant("endsAt") ?: 0, p.text("zoneId") ?: "UTC", p.bool("archived"), change.revision, updated, deleted, LocalSyncState.SYNCED))
        "budgetallocation" -> dao.putBudgetAllocation(BudgetAllocationRow(change.entityId, workspace, p.text("planId").orEmpty(), p.text("categoryId").orEmpty(), p.int("ownMinutes"), change.revision, updated, deleted, LocalSyncState.SYNCED))
        "plannedevent" -> dao.putPlannedEvent(PlannedEventRow(change.entityId, workspace, p.text("planId").orEmpty(), p.text("categoryTreeId").orEmpty(), p.text("categoryId"), p.text("taskId"), p.instant("occurredAt") ?: 0, p.text("note"), change.revision, updated, deleted, LocalSyncState.SYNCED))
    }
}

internal suspend fun deleteLocalEntity(dao: T4LDao, entityType: String, entityId: String) {
    when (entityType.lowercase()) {
        "categorytree" -> dao.hardDeleteCategoryTree(entityId)
        "category" -> dao.hardDeleteCategory(entityId)
        "event" -> dao.hardDeleteEvent(entityId)
        "task" -> dao.hardDeleteTask(entityId)
        "taskcomment" -> dao.hardDeleteTaskComment(entityId)
        "plan" -> dao.hardDeletePlan(entityId)
        "budgetallocation" -> dao.hardDeleteBudgetAllocation(entityId)
        "plannedevent" -> dao.hardDeletePlannedEvent(entityId)
    }
}

private fun JsonObject.text(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(name: String): Int = get(name)?.jsonPrimitive?.intOrNull ?: 0
private fun JsonObject.bool(name: String): Boolean = get(name)?.jsonPrimitive?.booleanOrNull ?: false
private fun JsonObject.instant(name: String): Long? = text(name)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
