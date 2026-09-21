package app.t4l.data

import androidx.room.withTransaction
import app.t4l.domain.CategoryNode
import app.t4l.domain.DeterministicIds
import app.t4l.domain.EventPoint
import app.t4l.domain.TimeEngine
import app.t4l.domain.Uuid7
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DashboardState(
    val categoryTrees: List<CategoryTreeRow> = emptyList(),
    val categories: List<CategoryRow> = emptyList(),
    val events: List<EventRow> = emptyList(),
) {
    fun currentEvent(categoryTreeId: String): EventRow? = events.filter { it.categoryTreeId == categoryTreeId }.maxByOrNull { it.occurredAtEpochMs }
}
data class TaskState(val tasks: List<TaskRow> = emptyList(), val comments: List<TaskCommentRow> = emptyList())
data class PlannerState(val plans: List<PlanRow> = emptyList(), val allocations: List<BudgetAllocationRow> = emptyList(), val plannedEvents: List<PlannedEventRow> = emptyList())
data class LocalReportRow(val categoryTreeId: String, val categoryId: String?, val seconds: Long)
data class WorkspaceOption(val id: String, val name: String, val role: String)
data class SyncUiState(
    val runtime: SyncRuntimeState = SyncRuntimeState(),
    val pending: List<OutboxRow> = emptyList(),
    val conflicts: List<ConflictRow> = emptyList(),
) {
    val failed: List<OutboxRow> get() = pending.filter { it.lastError != null }
}

@OptIn(ExperimentalCoroutinesApi::class)
class T4LRepository(
    private val database: T4LDatabase,
    private val identity: DeviceIdentity,
    private val syncStateStore: SyncStateStore,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    private val dao = database.dao()
    private val workspace = MutableStateFlow(identity.workspaceId)
    val availableWorkspaces = MutableStateFlow<List<WorkspaceOption>>(emptyList())
    val clientId: String get() = identity.clientId
    val workspaceId: String get() = workspace.value
    val userId: String get() = identity.userId

    val dashboard: Flow<DashboardState> = workspace.flatMapLatest { selected ->
        combine(dao.observeCategoryTrees(selected), dao.observeCategories(selected), dao.observeEvents(selected)) {
                trees, categories, events -> DashboardState(trees, categories, events)
        }
    }
    val taskState: Flow<TaskState> = workspace.flatMapLatest { selected ->
        combine(dao.observeTasks(selected), dao.observeTaskComments(selected), ::TaskState)
    }
    val plannerState: Flow<PlannerState> = workspace.flatMapLatest { selected ->
        combine(dao.observePlans(selected), dao.observeBudgetAllocations(selected), dao.observePlannedEvents(selected), ::PlannerState)
    }
    val syncState: Flow<SyncUiState> = workspace.flatMapLatest { selected ->
        combine(syncStateStore.state, dao.observeOutbox(selected), dao.observeConflicts(selected), ::SyncUiState)
    }

    fun selectWorkspace(selected: String) { identity.workspaceId = selected; workspace.value = selected }
    fun updateBootstrap(userId: String, defaultWorkspace: String, workspaces: List<WorkspaceOption>) {
        identity.userId = userId
        availableWorkspaces.value = workspaces
        if (workspaces.none { it.id == workspaceId }) selectWorkspace(defaultWorkspace)
    }

    suspend fun createStarterData(now: Long = System.currentTimeMillis()) = database.withTransaction {
        if (dao.categoryTreeCount(workspaceId) > 0) return@withTransaction
        createCategoryTreeInternal("Activity", "primary", listOf("Sleep", "Work", "Travel", "Rest", "Household", "Sport"), now)
        createCategoryTreeInternal("Location", "standard", listOf("Home", "Office", "Road"), now + 10)
    }

    suspend fun createCategoryTree(name: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(name.isNotBlank() && name.length <= 120)
        createCategoryTreeInternal(name.trim(), "standard", emptyList(), now)
    }

    suspend fun addCategory(categoryTreeId: String, name: String, parentId: String? = null, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(name.isNotBlank() && name.length <= 120)
        requireNotNull(dao.categoryTree(categoryTreeId))
        parentId?.let { require(dao.category(it)?.categoryTreeId == categoryTreeId) }
        val row = CategoryRow(Uuid7.new(now), workspaceId, categoryTreeId, parentId, name.trim(), updatedAtEpochMs = now)
        dao.putCategories(listOf(row)); dao.enqueue(outboxForCategory(row))
    }

    suspend fun renameCategory(id: String, name: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(name.isNotBlank() && name.length <= 120); val row = requireNotNull(dao.category(id)).copy(name = name.trim(), updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putCategories(listOf(row)); replacePending("category", id, outboxForCategory(row))
    }

    suspend fun renameCategoryTree(id: String, name: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(name.isNotBlank() && name.length <= 120); val row = requireNotNull(dao.categoryTree(id)).copy(name = name.trim(), updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putCategoryTree(row); replacePending("categoryTree", id, outboxForCategoryTree(row))
    }

    suspend fun archiveCategory(categoryId: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val queue = ArrayDeque<String>(); queue += categoryId
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst(); val row = requireNotNull(dao.category(id)); queue.addAll(dao.children(id).map { it.id })
            val updated = row.copy(archived = true, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
            dao.putCategories(listOf(updated)); replacePending("category", id, outboxForCategory(updated))
            pauseTasksForCategory(id, now)
        }
    }

    suspend fun archiveCategoryTree(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val row = requireNotNull(dao.categoryTree(id)); val updated = row.copy(archived = true, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putCategoryTree(updated); replacePending("categoryTree", id, outboxForCategoryTree(updated))
        dao.categoriesInTree(id).forEach { category ->
            val archived = category.copy(archived = true, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
            dao.putCategories(listOf(archived)); replacePending("category", category.id, outboxForCategory(archived))
            pauseTasksForCategory(category.id, now)
        }
    }

    suspend fun addEvent(categoryTreeId: String, categoryId: String?, at: Long, taskId: String? = null, note: String? = null, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(at <= now) { "A factual event cannot be in the future." }
        validateEventTarget(categoryTreeId, categoryId, taskId)
        val row = EventRow(Uuid7.new(now), workspaceId, categoryTreeId, categoryId, taskId, at, ZoneId.systemDefault().id, note = note?.trim()?.takeIf(String::isNotEmpty), updatedAtEpochMs = now)
        dao.putEvent(row); dao.enqueue(outboxForEvent(row))
    }

    suspend fun switchCategory(categoryTreeId: String, categoryId: String?, taskId: String? = null, at: Long = System.currentTimeMillis()) =
        addEvent(categoryTreeId, categoryId, at, taskId)

    suspend fun updateEvent(eventId: String, at: Long, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(at <= now) { "A factual event cannot be in the future." }
        val row = requireNotNull(dao.event(eventId)).copy(occurredAtEpochMs = at, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putEvent(row); replacePending("event", eventId, outboxForEvent(row))
    }

    suspend fun deleteEvent(eventId: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val row = requireNotNull(dao.event(eventId)); dao.putEvent(row.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING))
        replacePending("event", eventId, outbox("event", eventId, row.revision, buildJsonObject {}, now, "delete"))
    }

    suspend fun createTask(title: String, categoryId: String?, parentTaskId: String?, estimateMinutes: Int, nextActionDate: LocalDate,
        nextActionMinute: Int? = null, deadlineEpochMs: Long? = null, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(title.isNotBlank() && estimateMinutes >= 0)
        require((categoryId == null) != (parentTaskId == null)) { "A task must have exactly one parent." }
        parentTaskId?.let { requireNotNull(dao.task(it)) }
        categoryId?.let { requireNotNull(dao.category(it)) }
        val row = TaskRow(Uuid7.new(now), workspaceId, title.trim(), categoryId, parentTaskId, estimateMinutes, estimateMinutes,
            deadlineEpochMs = deadlineEpochMs, nextActionDateEpochDay = nextActionDate.toEpochDay(), nextActionMinuteOfDay = nextActionMinute,
            zoneId = ZoneId.systemDefault().id, updatedAtEpochMs = now)
        dao.putTask(row); dao.enqueue(outboxForTask(row))
    }

    suspend fun updateTask(id: String, title: String, categoryId: String?, estimateMinutes: Int, nextActionDate: LocalDate,
        nextActionMinute: Int?, deadlineEpochMs: Long?, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(title.isNotBlank() && estimateMinutes >= 0)
        val current = requireNotNull(dao.task(id)); val nextCategory = if (current.parentTaskId == null) requireNotNull(categoryId) else null
        nextCategory?.let { requireNotNull(dao.category(it)) }
        val updated = current.copy(title = title.trim(), categoryId = nextCategory, estimateMinutes = estimateMinutes,
            remainingEstimateMinutes = minOf(current.remainingEstimateMinutes, estimateMinutes), deadlineEpochMs = deadlineEpochMs,
            nextActionDateEpochDay = nextActionDate.toEpochDay(), nextActionMinuteOfDay = nextActionMinute,
            updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putTask(updated); replacePending("task", id, outboxForTask(updated))
    }

    suspend fun setTaskStatus(id: String, status: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(status in setOf("active", "paused", "completed", "cancelled"))
        val row = requireNotNull(dao.task(id)).copy(status = status, progress = if (status == "completed") 100 else dao.task(id)!!.progress,
            updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putTask(row); replacePending("task", id, outboxForTask(row))
    }

    suspend fun deleteTask(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val queue = ArrayDeque<String>(); queue += id
        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst(); queue.addAll(dao.taskChildren(currentId).map { it.id }); val row = requireNotNull(dao.task(currentId))
            dao.putTask(row.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING))
            replacePending("task", currentId, outbox("task", currentId, row.revision, buildJsonObject {}, now, "delete"))
        }
    }

    suspend fun addTaskComment(taskId: String, text: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(text.isNotBlank()); requireNotNull(dao.task(taskId))
        val row = TaskCommentRow(Uuid7.new(now), workspaceId, taskId, userId, text.trim(), updatedAtEpochMs = now)
        dao.putTaskComment(row); dao.enqueue(outboxForTaskComment(row))
    }

    suspend fun startTask(taskId: String, now: Long = System.currentTimeMillis()) {
        val task = requireNotNull(dao.task(taskId)); require(task.status == "active")
        val category = effectiveCategory(task); requireNotNull(category); require(!category.archived)
        require(dao.categoryTree(category.categoryTreeId)?.archived == false)
        switchCategory(category.categoryTreeId, category.id, task.id, now)
    }

    suspend fun createPlan(name: String, kind: String, startsAt: Long, endsAt: Long, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(name.isNotBlank() && kind in setOf("budget", "timeline") && endsAt > startsAt)
        val row = PlanRow(Uuid7.new(now), workspaceId, name.trim(), kind, startsAt, endsAt, ZoneId.systemDefault().id, updatedAtEpochMs = now)
        dao.putPlan(row); dao.enqueue(outboxForPlan(row))
    }

    suspend fun archivePlan(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val current = requireNotNull(dao.plan(id)); val updated = current.copy(archived = true, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putPlan(updated); replacePending("plan", id, outboxForPlan(updated))
    }

    suspend fun setBudget(planId: String, categoryId: String, minutes: Int, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(minutes >= 0); val plan = requireNotNull(dao.plan(planId)); require(plan.kind == "budget")
        val current = dao.budgetAllocation(planId, categoryId)
        val row = current?.copy(ownMinutes = minutes, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
            ?: BudgetAllocationRow(DeterministicIds.budgetAllocation(workspaceId, planId, categoryId), workspaceId, planId, categoryId, minutes, updatedAtEpochMs = now)
        dao.putBudgetAllocation(row); replacePending("budgetAllocation", row.id, outboxForBudget(row))
    }

    suspend fun resolveConflictUseLocal(clientMutationId: String, editedPayload: String? = null, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val conflict = requireNotNull(dao.conflict(clientMutationId))
        val payload = editedPayload ?: conflict.localPayloadJson
        require(runCatching { json.parseToJsonElement(payload) is JsonObject }.getOrDefault(false))
        dao.enqueue(OutboxRow(Uuid7.new(now), clientId, conflict.workspaceId, conflict.entityType, conflict.entityId,
            conflict.operation, conflict.serverRevision, payload, now))
        dao.deleteConflict(clientMutationId)
    }

    suspend fun resolveConflictUseServer(clientMutationId: String) = database.withTransaction {
        val conflict = requireNotNull(dao.conflict(clientMutationId))
        dao.deletePendingForEntity(conflict.entityType, conflict.entityId)
        if (conflict.serverPayloadJson.isBlank()) {
            deleteLocalEntity(dao, conflict.entityType, conflict.entityId)
        } else {
            val payload = json.parseToJsonElement(conflict.serverPayloadJson) as JsonObject
            applyApiChange(dao, conflict.workspaceId, ApiChange(0, conflict.entityType, conflict.entityId, conflict.serverRevision, false, payload))
        }
        dao.deleteConflict(clientMutationId)
    }

    suspend fun retryRejected(clientMutationId: String, editedPayload: String? = null) = database.withTransaction {
        if (editedPayload != null) {
            require(runCatching { json.parseToJsonElement(editedPayload) is JsonObject }.getOrDefault(false))
            val current = requireNotNull(dao.pendingMutation(clientMutationId))
            dao.putOutbox(current.copy(payloadJson = editedPayload, attemptCount = 0, lastError = null))
        } else dao.retryMutation(clientMutationId)
    }

    suspend fun addPlannedEvent(planId: String, categoryTreeId: String, categoryId: String?, at: Long, taskId: String? = null, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val plan = requireNotNull(dao.plan(planId)); require(plan.kind == "timeline" && at >= plan.startsAtEpochMs && at < plan.endsAtEpochMs)
        validateEventTarget(categoryTreeId, categoryId, taskId)
        val row = PlannedEventRow(Uuid7.new(now), workspaceId, planId, categoryTreeId, categoryId, taskId, at, updatedAtEpochMs = now)
        dao.putPlannedEvent(row); dao.enqueue(outboxForPlannedEvent(row))
    }

    suspend fun updatePlannedEvent(id: String, at: Long, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val current = requireNotNull(dao.plannedEvent(id)); val plan = requireNotNull(dao.plan(current.planId)); require(at >= plan.startsAtEpochMs && at < plan.endsAtEpochMs)
        val updated = current.copy(occurredAtEpochMs = at, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putPlannedEvent(updated); replacePending("plannedEvent", id, outboxForPlannedEvent(updated))
    }

    suspend fun deletePlannedEvent(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val current = requireNotNull(dao.plannedEvent(id)); dao.putPlannedEvent(current.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING))
        replacePending("plannedEvent", id, outbox("plannedEvent", id, current.revision, buildJsonObject {}, now, "delete"))
    }

    fun report(state: DashboardState, from: Long, to: Long, now: Long): List<LocalReportRow> = TimeEngine.aggregate(
        TimeEngine.intervals(state.events.map { EventPoint(it.id, it.categoryTreeId, it.categoryId, it.occurredAtEpochMs) }, from, to, now),
        state.categories.map { CategoryNode(it.id, it.categoryTreeId, it.parentId) },
    ).map { LocalReportRow(it.timelineId, it.categoryId, it.durationSeconds) }

    private suspend fun effectiveCategory(task: TaskRow, visited: MutableSet<String> = mutableSetOf()): CategoryRow? {
        require(visited.add(task.id)) { "Task hierarchy contains a cycle." }
        return task.categoryId?.let { dao.category(it) }
            ?: task.parentTaskId?.let { dao.task(it) }?.let { effectiveCategory(it, visited) }
    }
    private suspend fun pauseTasksForCategory(categoryId: String, now: Long) {
        dao.tasksForCategory(categoryId).filter { it.status == "active" }.forEach { task ->
            val paused = task.copy(status = "paused", updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
            dao.putTask(paused); replacePending("task", task.id, outboxForTask(paused))
        }
    }
    private suspend fun validateEventTarget(treeId: String, categoryId: String?, taskId: String?) {
        categoryId?.let { require(dao.category(it)?.categoryTreeId == treeId) }
        taskId?.let { require(effectiveCategory(requireNotNull(dao.task(it)))?.id == categoryId) }
    }
    private suspend fun replacePending(type: String, id: String, row: OutboxRow) { dao.deletePendingForEntity(type, id); dao.enqueue(row) }
    private suspend fun createCategoryTreeInternal(name: String, role: String, categoryNames: List<String>, now: Long) {
        val tree = CategoryTreeRow(Uuid7.new(now), workspaceId, name, role, updatedAtEpochMs = now)
        dao.putCategoryTree(tree); dao.enqueue(outboxForCategoryTree(tree))
        categoryNames.forEachIndexed { index, value ->
            val row = CategoryRow(Uuid7.new(now + index + 1), workspaceId, tree.id, name = value, sortOrder = index, updatedAtEpochMs = now)
            dao.putCategories(listOf(row)); dao.enqueue(outboxForCategory(row))
        }
    }

    private fun outbox(type: String, id: String, revision: Long, payload: JsonObject, now: Long, operation: String = "upsert") =
        OutboxRow(Uuid7.new(now), clientId, workspaceId, type, id, operation, revision, payload.toString(), now)
    private fun outboxForCategoryTree(r: CategoryTreeRow) = outbox("categoryTree", r.id, r.revision, buildJsonObject { put("name", r.name); put("role", r.role); put("sortOrder", r.sortOrder); put("archived", r.archived) }, r.updatedAtEpochMs)
    private fun outboxForCategory(r: CategoryRow) = outbox("category", r.id, r.revision, buildJsonObject { put("categoryTreeId", r.categoryTreeId); putNullable("parentId", r.parentId); put("name", r.name); put("loadType", r.loadType); put("sortOrder", r.sortOrder); put("archived", r.archived) }, r.updatedAtEpochMs)
    private fun outboxForEvent(r: EventRow) = outbox("event", r.id, r.revision, buildJsonObject { put("categoryTreeId", r.categoryTreeId); putNullable("categoryId", r.categoryId); putNullable("taskId", r.taskId); put("occurredAt", Instant.ofEpochMilli(r.occurredAtEpochMs).toString()); put("zoneId", r.zoneId); put("source", r.source); putNullable("note", r.note) }, r.updatedAtEpochMs)
    private fun outboxForTask(r: TaskRow) = outbox("task", r.id, r.revision, buildJsonObject { put("title", r.title); putNullable("categoryId", r.categoryId); putNullable("parentTaskId", r.parentTaskId); put("estimateMinutes", r.estimateMinutes); put("remainingEstimateMinutes", r.remainingEstimateMinutes); r.deadlineEpochMs?.let { put("deadline", Instant.ofEpochMilli(it).toString()) }; r.nextActionDateEpochDay?.let { put("nextActionDate", LocalDate.ofEpochDay(it).toString()) }; r.nextActionMinuteOfDay?.let { put("nextActionTime", "%02d:%02d:00".format(it / 60, it % 60)) }; put("zoneId", r.zoneId); put("value", r.value); put("energy", r.energy); put("progress", r.progress); put("status", r.status); put("splittable", r.splittable) }, r.updatedAtEpochMs)
    private fun outboxForTaskComment(r: TaskCommentRow) = outbox("taskComment", r.id, r.revision, buildJsonObject { put("taskId", r.taskId); put("authorId", r.authorId); put("text", r.text) }, r.updatedAtEpochMs)
    private fun outboxForPlan(r: PlanRow) = outbox("plan", r.id, r.revision, buildJsonObject { put("name", r.name); put("kind", r.kind); put("startsAt", Instant.ofEpochMilli(r.startsAtEpochMs).toString()); put("endsAt", Instant.ofEpochMilli(r.endsAtEpochMs).toString()); put("zoneId", r.zoneId); put("archived", r.archived) }, r.updatedAtEpochMs)
    private fun outboxForBudget(r: BudgetAllocationRow) = outbox("budgetAllocation", r.id, r.revision, buildJsonObject { put("planId", r.planId); put("categoryId", r.categoryId); put("ownMinutes", r.ownMinutes) }, r.updatedAtEpochMs)
    private fun outboxForPlannedEvent(r: PlannedEventRow) = outbox("plannedEvent", r.id, r.revision, buildJsonObject { put("planId", r.planId); put("categoryTreeId", r.categoryTreeId); putNullable("categoryId", r.categoryId); putNullable("taskId", r.taskId); put("occurredAt", Instant.ofEpochMilli(r.occurredAtEpochMs).toString()); putNullable("note", r.note) }, r.updatedAtEpochMs)
    private fun JsonObjectBuilder.putNullable(name: String, value: String?) { put(name, value?.let(::JsonPrimitive) ?: JsonNull) }

    companion object {
        const val DEFAULT_WORKSPACE_ID = "018f0000-0000-7000-8000-000000000002"
        const val DEVELOPMENT_USER_ID = "018f0000-0000-7000-8000-000000000001"
    }
}
