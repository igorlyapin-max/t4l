package app.t4l.data

import androidx.room.withTransaction
import app.t4l.domain.CategoryNode
import app.t4l.domain.DeterministicIds
import app.t4l.domain.EventPoint
import app.t4l.domain.TimeEngine
import app.t4l.reorderedTaskIds
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class DashboardState(
    val categoryTrees: List<CategoryTreeRow> = emptyList(),
    val categories: List<CategoryRow> = emptyList(),
    val events: List<EventRow> = emptyList(),
    val trashedCategoryTrees: List<CategoryTreeRow> = emptyList(),
    val historicalCategoryTrees: List<CategoryTreeRow> = emptyList(),
    val treeAppearances: List<TreeAppearanceRow> = emptyList(),
    val palettes: List<PaletteRow> = emptyList(),
    val trashedPalettes: List<PaletteRow> = emptyList(),
) {
    fun currentEvent(categoryTreeId: String): EventRow? = events.filter { it.categoryTreeId == categoryTreeId }.maxByOrNull { it.occurredAtEpochMs }
}
data class TaskState(val tasks: List<TaskRow> = emptyList(), val comments: List<TaskCommentRow> = emptyList())
data class PlannerState(val plans: List<PlanRow> = emptyList(), val allocations: List<BudgetAllocationRow> = emptyList(), val plannedEvents: List<PlannedEventRow> = emptyList(), val archivedPlans: List<PlanRow> = emptyList(), val loaded: Boolean = false)
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
    private val syncLimitProvider: () -> Int = { 100 },
) {
    private val dao = database.dao()
    private val workspace = MutableStateFlow(identity.workspaceId)
    val availableWorkspaces = MutableStateFlow<List<WorkspaceOption>>(emptyList())
    val clientId: String get() = identity.clientId
    val workspaceId: String get() = workspace.value
    val userId: String get() = identity.userId

    val dashboard: Flow<DashboardState> = workspace.flatMapLatest { selected ->
        val core = combine(dao.observeCategoryTrees(selected), dao.observeCategories(selected), dao.observeEvents(selected), dao.observeTrashedCategoryTrees(selected), dao.observeHistoricalCategoryTrees(selected)) {
                trees, categories, events, trashed, historical -> DashboardState(trees, categories, events, trashed, historical)
        }
        combine(core, dao.observeTreeAppearances(selected), dao.observePalettes(selected), dao.observeTrashedPalettes(selected)) { dashboard, appearances, palettes, trashed ->
            dashboard.copy(treeAppearances = appearances, palettes = palettes, trashedPalettes = trashed)
        }
    }
    val taskState: Flow<TaskState> = workspace.flatMapLatest { selected ->
        combine(dao.observeTasks(selected), dao.observeTaskComments(selected), ::TaskState)
    }
    val plannerState: Flow<PlannerState> = workspace.flatMapLatest { selected ->
        combine(dao.observePlans(selected), dao.observeBudgetAllocations(selected), dao.observePlannedEvents(selected), dao.observeArchivedPlans(selected)) { plans, allocations, events, archived ->
            PlannerState(plans, allocations, events, archived, loaded = true)
        }
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
        require(dao.categoryTree(categoryTreeId)?.let { !it.archived && it.purgedAtEpochMs == null && it.deletedAtEpochMs == null } == true)
        parentId?.let { require(dao.category(it)?.categoryTreeId == categoryTreeId) }
        val sortOrder = dao.categoriesInTree(categoryTreeId)
            .filter { !it.archived && it.deletedAtEpochMs == null && it.parentId == parentId }
            .maxOfOrNull { it.sortOrder }
            ?.plus(10)
            ?: 0
        val row = CategoryRow(Uuid7.new(now), workspaceId, categoryTreeId, parentId, name.trim(), sortOrder = sortOrder, updatedAtEpochMs = now)
        dao.putCategories(listOf(row)); dao.enqueue(outboxForCategory(row))
    }

    suspend fun moveCategory(categoryId: String, placement: CategoryPlacement, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val moving = requireNotNull(dao.category(categoryId))
        val updates = moveCategoryRows(dao.categoriesInTree(moving.categoryTreeId), categoryId, placement, now)
        if (updates.isNotEmpty()) {
            dao.putCategories(updates)
            updates.forEach { row -> replacePending("category", row.id, outboxForCategory(row)) }
        }
    }

    suspend fun renameCategory(id: String, name: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(name.isNotBlank() && name.length <= 120); val row = requireNotNull(dao.category(id)).copy(name = name.trim(), updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putCategories(listOf(row)); replacePending("category", id, outboxForCategory(row))
    }

    suspend fun renameCategoryTree(id: String, name: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(name.isNotBlank() && name.length <= 120); val row = requireNotNull(dao.categoryTree(id)).copy(name = name.trim(), updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putCategoryTree(row); replacePending("categoryTree", id, outboxForCategoryTree(row))
    }

    suspend fun setTreeColor(treeId: String, colorHex: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(validHexColor(colorHex))
        require(dao.categoryTree(treeId)?.let { it.workspaceId == workspaceId && !it.archived && it.deletedAtEpochMs == null } == true)
        val current = dao.treeAppearance(treeId)
        val row = TreeAppearanceRow(treeId, workspaceId, treeId, colorHex.uppercase(), current?.revision ?: 0,
            now, syncState = LocalSyncState.PENDING)
        dao.putTreeAppearance(row)
        replacePending("treeAppearance", treeId, outboxForTreeAppearance(row))
    }

    suspend fun createPalette(name: String, now: Long = System.currentTimeMillis()): String = database.withTransaction {
        require(name.isNotBlank() && name.length <= 120)
        val row = PaletteRow(Uuid7.new(now), workspaceId, name.trim(), updatedAtEpochMs = now)
        dao.putPalette(row); dao.enqueue(outboxForPalette(row)); row.id
    }

    suspend fun renamePalette(id: String, name: String, now: Long = System.currentTimeMillis()) = updatePalette(id, now) { row, _ ->
        require(name.isNotBlank() && name.length <= 120)
        row.copy(name = name.trim())
    }

    suspend fun addPaletteCategory(id: String, categoryId: String, now: Long = System.currentTimeMillis()) = updatePalette(id, now) { row, contents ->
        requireActiveCategory(categoryId)
        require(categoryId !in contents.categoryColors)
        val changed = contents.copy(categoryColors = contents.categoryColors + (categoryId to null), order = contents.order + "c:$categoryId",
            rows = contents.rows + PaletteLayoutRow(Uuid7.new(now), listOf("c:$categoryId")))
        row.copy(categoryColorsJson = changed.colorsJson(), itemOrderJson = changed.orderJson(), rowsJson = changed.rowsJson())
    }

    suspend fun removePaletteCategory(id: String, categoryId: String, now: Long = System.currentTimeMillis()) = updatePalette(id, now) { row, contents ->
        require(categoryId in contents.categoryColors)
        val changed = contents.copy(categoryColors = contents.categoryColors - categoryId, order = contents.order - "c:$categoryId",
            rows = contents.rows.map { line -> line.copy(slots = line.slots.map { if (it == "c:$categoryId") null else it }) })
        row.copy(categoryColorsJson = changed.colorsJson(), itemOrderJson = changed.orderJson(), rowsJson = changed.rowsJson())
    }

    suspend fun setPaletteCategoryColor(id: String, categoryId: String, colorHex: String?, now: Long = System.currentTimeMillis()) = updatePalette(id, now) { row, contents ->
        require(categoryId in contents.categoryColors)
        require(colorHex == null || validHexColor(colorHex))
        row.copy(categoryColorsJson = contents.copy(categoryColors = contents.categoryColors + (categoryId to colorHex?.uppercase())).colorsJson())
    }

    suspend fun reorderPalette(id: String, order: List<String>, now: Long = System.currentTimeMillis()) = updatePalette(id, now) { row, contents ->
        require(order.distinct().size == order.size)
        require(order.filter { it.startsWith("c:") }.toSet() == contents.categoryColors.keys.map { "c:$it" }.toSet())
        require(order.all { it.startsWith("c:") || it.startsWith("t:") })
        val values = order.iterator()
        val rows = contents.rows.map { line -> line.copy(slots = line.slots.map { if (it == null) null else values.next() }) }
        val changed = contents.copy(order = order, rows = rows)
        row.copy(itemOrderJson = changed.orderJson(), rowsJson = changed.rowsJson())
    }

    suspend fun addPaletteRow(id: String, now: Long = System.currentTimeMillis()) = updatePalette(id, now) { row, contents ->
        row.copy(rowsJson = contents.copy(rows = contents.rows + PaletteLayoutRow(Uuid7.new(now), listOf(null))).rowsJson())
    }

    suspend fun removeEmptyPaletteRow(id: String, rowId: String, now: Long = System.currentTimeMillis()) = updatePalette(id, now) { row, contents ->
        require(contents.rows.firstOrNull { it.id == rowId }?.slots?.all { it == null } == true)
        row.copy(rowsJson = contents.copy(rows = contents.rows.filterNot { it.id == rowId }).rowsJson())
    }

    suspend fun movePaletteItem(id: String, moving: String, targetRowId: String, targetSlot: Int,
        now: Long = System.currentTimeMillis()) = updatePalette(id, now) { row, contents ->
        require(contents.rows.any { moving in it.slots })
        val changed = contents.moved(moving, targetRowId, targetSlot)
        require(changed !== contents)
        row.copy(itemOrderJson = changed.orderJson(), rowsJson = changed.rowsJson())
    }

    suspend fun ensurePaletteTaskRows(id: String, taskKeys: List<String>, now: Long = System.currentTimeMillis()) = updatePalette(id, now) { row, contents ->
        val missing = taskKeys.distinct().filterNot { it in contents.order }
        val changed = contents.copy(order = contents.order + missing,
            rows = contents.rows + missing.mapIndexed { index, key -> PaletteLayoutRow(Uuid7.new(now + index), listOf(key)) })
        row.copy(itemOrderJson = changed.orderJson(), rowsJson = changed.rowsJson())
    }

    suspend fun archivePalette(id: String, now: Long = System.currentTimeMillis()) = updatePalette(id, now) { row, _ ->
        row.copy(archived = true, trashedAtEpochMs = now)
    }

    suspend fun restorePalette(id: String, now: Long = System.currentTimeMillis()) = updatePalette(id, now, allowArchived = true) { row, _ ->
        require(row.archived && row.purgedAtEpochMs == null && row.trashedAtEpochMs?.let { now - it < 30L * 24 * 60 * 60 * 1000 } == true)
        row.copy(archived = false, trashedAtEpochMs = null)
    }

    suspend fun purgePalette(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val row = requireNotNull(dao.palette(id))
        require(row.workspaceId == workspaceId && row.archived && row.purgedAtEpochMs == null && row.syncState == LocalSyncState.SYNCED)
        dao.putPalette(row.copy(purgedAtEpochMs = now, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING))
        replacePending("palette", id, outbox("palette", id, row.revision, buildJsonObject {}, now, "purge"))
    }

    private suspend fun updatePalette(id: String, now: Long, allowArchived: Boolean = false, change: suspend (PaletteRow, PaletteContents) -> PaletteRow) = database.withTransaction {
        val current = requireNotNull(dao.palette(id))
        require(current.workspaceId == workspaceId && current.deletedAtEpochMs == null && current.purgedAtEpochMs == null && (allowArchived || !current.archived))
        val row = change(current, PaletteContents.from(current)).copy(updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putPalette(row); replacePending("palette", id, outboxForPalette(row))
    }

    suspend fun archiveCategory(categoryId: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val queue = ArrayDeque<String>(); queue += categoryId
        val subtree = mutableListOf<CategoryRow>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst(); val row = requireNotNull(dao.category(id)); queue.addAll(dao.children(id).map { it.id }); subtree += row
        }
        require(subtree.size <= syncLimitProvider()) { "Category subtree exceeds server sync batch limit (${syncLimitProvider()})." }
        val groupId = Uuid7.new(now)
        subtree.forEachIndexed { index, row ->
            val updated = row.copy(archived = true, trashedAtEpochMs = row.trashedAtEpochMs ?: now,
                updatedAtEpochMs = now + index, syncState = LocalSyncState.PENDING)
            dao.putCategories(listOf(updated)); replacePending("category", row.id, outboxForCategory(updated).copy(atomicGroupId = groupId))
        }
        subtree.forEach { pauseTasksForCategory(it.id, now, groupId) }
    }

    suspend fun archiveCategoryTree(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val row = requireNotNull(dao.categoryTree(id))
        dao.categoriesInTree(id).forEach { category ->
            pauseTasksForCategory(category.id, now)
        }
        val updated = row.copy(archived = true, trashedAtEpochMs = now, updatedAtEpochMs = now + 1, syncState = LocalSyncState.PENDING)
        dao.putCategoryTree(updated); replacePending("categoryTree", id, outboxForCategoryTree(updated))
    }

    suspend fun restoreCategoryTree(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val row = requireNotNull(dao.categoryTree(id))
        require(row.archived && row.purgedAtEpochMs == null && row.trashedAtEpochMs != null && now - row.trashedAtEpochMs < 30L * 24 * 60 * 60 * 1000)
        val restored = row.copy(archived = false, trashedAtEpochMs = null, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putCategoryTree(restored); replacePending("categoryTree", id, outboxForCategoryTree(restored))
    }

    suspend fun restoreCategory(categoryId: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val target = requireNotNull(dao.category(categoryId))
        val tree = requireNotNull(dao.categoryTree(target.categoryTreeId))
        require(tree.workspaceId == workspaceId && !tree.archived && tree.purgedAtEpochMs == null)
        val ancestors = mutableListOf<CategoryRow>()
        val seen = mutableSetOf<String>()
        var current: CategoryRow? = target
        while (current != null && seen.add(current.id)) {
            ancestors += current
            current = current.parentId?.let { dao.category(it) }
        }
        require(current == null) { "Category parent cycle" }
        require(ancestors.count { it.archived } <= syncLimitProvider()) { "Category restore path exceeds server sync batch limit" }
        require(ancestors.all { it.workspaceId == workspaceId && it.categoryTreeId == target.categoryTreeId &&
            it.deletedAtEpochMs == null && it.purgedAtEpochMs == null &&
            (!it.archived || it.trashedAtEpochMs?.let { archivedAt -> now - archivedAt < 48L * 60 * 60 * 1000 } == true) })
        val groupId = Uuid7.new(now)
        ancestors.asReversed().filter { it.archived }.forEachIndexed { index, category ->
            val restored = category.copy(archived = false, trashedAtEpochMs = null, updatedAtEpochMs = now + index,
                syncState = LocalSyncState.PENDING)
            dao.putCategories(listOf(restored))
            replacePending("category", restored.id, outboxForCategory(restored).copy(atomicGroupId = groupId))
        }
    }

    suspend fun purgeCategoryTree(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val row = requireNotNull(dao.categoryTree(id))
        require(row.archived && row.purgedAtEpochMs == null && row.syncState == LocalSyncState.SYNCED && !dao.hasPendingForEntity("categoryTree", id))
        require(dao.categoriesInTree(id).none { it.syncState != LocalSyncState.SYNCED || dao.hasPendingForEntity("category", it.id) })
        dao.putCategoryTree(row.copy(purgedAtEpochMs = now, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING))
        replacePending("categoryTree", id, outbox("categoryTree", id, row.revision, buildJsonObject {}, now, "purge"))
    }

    suspend fun addEvent(categoryTreeId: String, categoryId: String?, at: Long, taskId: String? = null, note: String? = null, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(at <= now) { "A factual event cannot be in the future." }
        validateEventTarget(categoryTreeId, categoryId, taskId, requireActive = true)
        val row = EventRow(Uuid7.new(now), workspaceId, categoryTreeId, categoryId, taskId, at, ZoneId.systemDefault().id, note = note?.trim()?.takeIf(String::isNotEmpty), updatedAtEpochMs = now)
        dao.putEvent(row); dao.enqueue(outboxForEvent(row))
    }

    suspend fun switchCategory(categoryTreeId: String, categoryId: String?, taskId: String? = null, at: Long = System.currentTimeMillis()) =
        addEvent(categoryTreeId, categoryId, at, taskId)

    suspend fun updateEvent(eventId: String, categoryTreeId: String, categoryId: String?, taskId: String?, at: Long, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(at <= now) { "A factual event cannot be in the future." }
        val current = requireNotNull(dao.event(eventId))
        require(current.workspaceId == workspaceId && current.deletedAtEpochMs == null)
        val historicalTarget = current.categoryTreeId == categoryTreeId && current.categoryId == categoryId
        validateEventTarget(categoryTreeId, categoryId, taskId, requireActive = !historicalTarget || taskId != null && taskId != current.taskId)
        val row = current.copy(categoryTreeId = categoryTreeId, categoryId = categoryId, taskId = taskId,
            occurredAtEpochMs = at, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
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
        categoryId?.let { requireActiveCategory(it) }
        val sortOrder = (dao.tasks(workspaceId).maxOfOrNull { it.sortOrder } ?: -10) + 10
        val row = TaskRow(Uuid7.new(now), workspaceId, title.trim(), categoryId, parentTaskId, estimateMinutes,
            deadlineEpochMs = deadlineEpochMs, nextActionDateEpochDay = nextActionDate.toEpochDay(), nextActionMinuteOfDay = nextActionMinute,
            zoneId = ZoneId.systemDefault().id, sortOrder = sortOrder, updatedAtEpochMs = now)
        dao.putTask(row); dao.enqueue(outboxForTask(row))
    }

    suspend fun updateTask(id: String, title: String, categoryId: String?, estimateMinutes: Int, nextActionDate: LocalDate,
        nextActionMinute: Int?, deadlineEpochMs: Long?, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(title.isNotBlank() && estimateMinutes >= 0)
        val current = requireNotNull(dao.task(id)); val nextCategory = if (current.parentTaskId == null) requireNotNull(categoryId) else null
        nextCategory?.let { if (it != current.categoryId) requireActiveCategory(it) }
        val updated = current.copy(title = title.trim(), categoryId = nextCategory, estimateMinutes = estimateMinutes,
            deadlineEpochMs = deadlineEpochMs,
            nextActionDateEpochDay = nextActionDate.toEpochDay(), nextActionMinuteOfDay = nextActionMinute,
            updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putTask(updated); replacePending("task", id, outboxForTask(updated))
    }

    suspend fun renameTask(id: String, title: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(title.isNotBlank() && title.length <= 240)
        val updated = requireNotNull(dao.task(id)).copy(title = title.trim(), updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putTask(updated); replacePending("task", id, outboxForTask(updated))
    }

    suspend fun updateTaskDetails(
        id: String,
        title: String,
        categoryId: String?,
        parentTaskId: String?,
        estimateMinutes: Int,
        nextActionDate: LocalDate?,
        nextActionMinute: Int?,
        deadlineEpochMs: Long?,
        value: Int,
        energy: String,
        progress: Int,
        status: String,
        splittable: Boolean,
        now: Long = System.currentTimeMillis(),
    ) = database.withTransaction {
        require(title.isNotBlank() && title.length <= 240)
        require(estimateMinutes >= 0)
        require(value in 0..100 && progress in 0..100)
        require(energy in setOf("low", "medium", "high"))
        require(status in setOf("active", "paused", "completed", "cancelled"))
        require((categoryId == null) != (parentTaskId == null)) { "A task must have exactly one parent." }
        if (status == "active") requireNotNull(nextActionDate) { "An active task requires a next action date." }
        categoryId?.let { if (it != dao.task(id)?.categoryId) requireActiveCategory(it) }
        parentTaskId?.let { parentId ->
            require(parentId != id) { "A task cannot be its own parent." }
            var current: String? = parentId
            val visited = mutableSetOf(id)
            while (current != null) {
                require(visited.add(current)) { "Task hierarchy contains a cycle." }
                current = requireNotNull(dao.task(current)).parentTaskId
            }
        }
        val current = requireNotNull(dao.task(id))
        val updated = current.copy(
            title = title.trim(), categoryId = categoryId, parentTaskId = parentTaskId,
            estimateMinutes = estimateMinutes,
            deadlineEpochMs = deadlineEpochMs, nextActionDateEpochDay = nextActionDate?.toEpochDay(), nextActionMinuteOfDay = nextActionMinute,
            value = value, energy = energy, progress = if (status == "completed") 100 else progress,
            status = status, splittable = splittable, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING,
        )
        val transitioning = current.status == "active" && status != "active"
        val groupId = if (transitioning && current.revision > 0) Uuid7.new(now) else null
        if (transitioning) closeTaskInterval(current, now, groupId)
        dao.putTask(updated); replacePending("task", id, outboxForTask(updated, groupId))
    }

    suspend fun reorderTask(id: String, targetId: String, after: Boolean, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(id != targetId)
        val tasks = dao.tasks(workspaceId)
        val byId = tasks.associateBy { it.id }
        reorderedTaskIds(tasks, id, targetId, after).forEachIndexed { index, taskId ->
            val task = requireNotNull(byId[taskId])
            val nextOrder = index * 10
            if (task.sortOrder != nextOrder) {
                val updated = task.copy(sortOrder = nextOrder, updatedAtEpochMs = now + index, syncState = LocalSyncState.PENDING)
                dao.putTask(updated); replacePending("task", task.id, outboxForTask(updated))
            }
        }
    }

    suspend fun setTaskStatus(id: String, status: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(status in setOf("active", "paused", "completed", "cancelled"))
        val current = requireNotNull(dao.task(id))
        val row = current.copy(status = status, progress = if (status == "completed") 100 else current.progress,
            updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        val transitioning = current.status == "active" && status != "active"
        val groupId = if (transitioning && current.revision > 0) Uuid7.new(now) else null
        if (transitioning) closeTaskInterval(current, now, groupId)
        dao.putTask(row); replacePending("task", id, outboxForTask(row, groupId))
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

    suspend fun createPlan(name: String, startsAt: Long, endsAt: Long, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(name.isNotBlank() && endsAt > startsAt)
        val row = PlanRow(Uuid7.new(now), workspaceId, name.trim(), startsAt, endsAt, ZoneId.systemDefault().id, updatedAtEpochMs = now)
        dao.putPlan(row); dao.enqueue(outboxForPlan(row))
        row.id
    }

    suspend fun archivePlan(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val current = requireNotNull(dao.plan(id)); val updated = current.copy(archived = true, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putPlan(updated); replacePending("plan", id, outboxForPlan(updated))
    }

    suspend fun restorePlan(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val row = requireNotNull(dao.plan(id)); require(row.archived && row.deletedAtEpochMs == null)
        val updated = row.copy(archived = false, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putPlan(updated); replacePending("plan", id, outboxForPlan(updated))
    }

    suspend fun deletePlan(id: String, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val row = requireNotNull(dao.plan(id)); require(row.archived && row.deletedAtEpochMs == null)
        dao.budgetsForPlan(id).forEach { budget ->
            if (budget.revision == 0L) {
                dao.deletePendingForEntity("budgetAllocation", budget.id)
                dao.hardDeleteBudgetAllocation(budget.id)
            } else {
                dao.putBudgetAllocation(budget.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING))
                replacePending("budgetAllocation", budget.id, outbox("budgetAllocation", budget.id, budget.revision, buildJsonObject {}, now, "delete"))
            }
        }
        dao.plannedEventsForPlan(id).forEach { event ->
            if (event.revision == 0L) {
                dao.deletePendingForEntity("plannedEvent", event.id)
                dao.hardDeletePlannedEvent(event.id)
            } else {
                dao.putPlannedEvent(event.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING))
                replacePending("plannedEvent", event.id, outbox("plannedEvent", event.id, event.revision, buildJsonObject {}, now, "delete"))
            }
        }
        if (row.revision == 0L) {
            dao.deletePendingForEntity("plan", id)
            dao.hardDeletePlan(id)
        } else {
            dao.putPlan(row.copy(deletedAtEpochMs = now, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING))
            replacePending("plan", id, outbox("plan", id, row.revision, buildJsonObject {}, now + 1, "delete"))
        }
    }

    suspend fun updatePlanPeriod(id: String, startsAt: Long, endsAt: Long, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(endsAt > startsAt)
        val current = requireNotNull(dao.plan(id))
        require(current.workspaceId == workspaceId && !current.archived && current.deletedAtEpochMs == null)
        val updated = current.copy(startsAtEpochMs = startsAt, endsAtEpochMs = endsAt, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
        dao.putPlan(updated); replacePending("plan", id, outboxForPlan(updated))
    }

    suspend fun setBudget(planId: String, categoryId: String, minutes: Int, now: Long = System.currentTimeMillis()) = database.withTransaction {
        require(minutes >= 0); requireNotNull(dao.plan(planId)); requireActiveCategory(categoryId)
        val current = dao.budgetAllocation(planId, categoryId)
        val row = current?.copy(ownMinutes = minutes, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
            ?: BudgetAllocationRow(DeterministicIds.budgetAllocation(workspaceId, planId, categoryId), workspaceId, planId, categoryId, minutes, updatedAtEpochMs = now)
        dao.putBudgetAllocation(row); replacePending("budgetAllocation", row.id, outboxForBudget(row))
    }

    suspend fun isCategoryGroupConflict(clientMutationId: String): Boolean {
        val conflict = dao.conflict(clientMutationId) ?: return false
        return conflict.entityType.equals("category", true) && dao.pendingMutation(clientMutationId)?.atomicGroupId != null
    }

    private fun snapshotEntities(snapshot: String, field: String): Map<String, JsonObject> {
        val items = json.parseToJsonElement(snapshot).jsonObject[field]?.jsonArray ?: return emptyMap()
        return items.map { it.jsonObject }.mapNotNull { item -> item["id"]?.jsonPrimitive?.contentOrNull?.let { it to item } }.toMap()
    }

    suspend fun resolveConflictUseLocal(clientMutationId: String, editedPayload: String? = null, now: Long = System.currentTimeMillis(), snapshot: String? = null) = database.withTransaction {
        val conflict = requireNotNull(dao.conflict(clientMutationId))
        val pending = dao.pendingMutation(clientMutationId)
        if (pending?.atomicGroupId != null && conflict.entityType.equals("category", true)) {
            val canonical = snapshotEntities(requireNotNull(snapshot), "categories")
            dao.pendingAtomicGroup(pending.atomicGroupId).forEach { member ->
                val revision = canonical[member.entityId]?.get("revision")?.jsonPrimitive?.longOrNull ?: 0
                dao.retryMutationWithRevision(member.clientMutationId, revision)
            }
            dao.deleteConflict(clientMutationId)
            return@withTransaction
        }
        val payload = editedPayload ?: conflict.localPayloadJson
        require(runCatching { json.parseToJsonElement(payload) is JsonObject }.getOrDefault(false))
        val localStatus = (json.parseToJsonElement(payload) as JsonObject)["status"]?.jsonPrimitive?.contentOrNull
        val serverStatus = runCatching { (json.parseToJsonElement(conflict.serverPayloadJson) as JsonObject)["status"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val groupId = if (conflict.linkedEventId != null || conflict.entityType.equals("task", true) && serverStatus == "active" && localStatus != "active") Uuid7.new(now) else null
        conflict.linkedEventId?.let { eventId ->
            val event = requireNotNull(dao.event(eventId)).copy(occurredAtEpochMs = now, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
            dao.putEvent(event)
            dao.enqueue(outboxForEvent(event, groupId))
        }
        dao.enqueue(OutboxRow(Uuid7.new(now), clientId, conflict.workspaceId, conflict.entityType, conflict.entityId,
            conflict.operation, conflict.serverRevision, payload, now, atomicGroupId = groupId))
        dao.deleteConflict(clientMutationId)
    }

    suspend fun resolveConflictUseServer(clientMutationId: String, snapshot: String? = null) = database.withTransaction {
        val conflict = requireNotNull(dao.conflict(clientMutationId))
        val pending = dao.pendingMutation(clientMutationId)
        if (pending?.atomicGroupId != null && conflict.entityType.equals("category", true)) {
            val categories = snapshotEntities(requireNotNull(snapshot), "categories")
            val tasks = snapshotEntities(requireNotNull(snapshot), "tasks")
            val events = snapshotEntities(snapshot, "events")
            val group = dao.pendingAtomicGroup(pending.atomicGroupId)
            val dependents = dao.pendingDependents(pending.atomicGroupId)
            for (member in group + dependents) {
                val canonical = when (member.entityType.lowercase()) {
                    "category" -> categories[member.entityId]
                    "task" -> tasks[member.entityId]
                    "event" -> events[member.entityId]
                    else -> null
                }
                if (canonical == null) deleteLocalEntity(dao, member.entityType, member.entityId)
                else applyApiChange(dao, conflict.workspaceId, ApiChange(0, member.entityType, member.entityId,
                    canonical["revision"]?.jsonPrimitive?.longOrNull ?: 0, false, canonical))
            }
            dao.deleteMutations((group + dependents).map { it.clientMutationId })
            dao.deleteConflict(clientMutationId)
            return@withTransaction
        }
        dao.deletePendingForEntity(conflict.entityType, conflict.entityId)
        conflict.linkedEventId?.let { eventId ->
            dao.deletePendingForEntity("event", eventId)
            deleteLocalEntity(dao, "event", eventId)
        }
        if (conflict.serverPayloadJson.isBlank()) {
            deleteLocalEntity(dao, conflict.entityType, conflict.entityId)
        } else {
            val payload = json.parseToJsonElement(conflict.serverPayloadJson) as JsonObject
            applyApiChange(dao, conflict.workspaceId, ApiChange(0, conflict.entityType, conflict.entityId, conflict.serverRevision, false, payload))
        }
        dao.deleteConflict(clientMutationId)
    }

    suspend fun retryRejected(clientMutationId: String, editedPayload: String? = null) = database.withTransaction {
        val current = requireNotNull(dao.pendingMutation(clientMutationId))
        require(current.atomicGroupId == null || !dao.hasConflictForAtomicGroup(current.atomicGroupId)) {
            "Resolve the complete atomic group conflict first."
        }
        if (editedPayload != null) {
            require(runCatching { json.parseToJsonElement(editedPayload) is JsonObject }.getOrDefault(false))
            dao.putOutbox(current.copy(payloadJson = editedPayload, attemptCount = 0, lastError = null))
        } else dao.retryMutation(clientMutationId)
    }

    suspend fun addPlannedEvent(planId: String, categoryTreeId: String, categoryId: String?, at: Long, taskId: String? = null, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val plan = requireNotNull(dao.plan(planId)); require(at >= plan.startsAtEpochMs && at < plan.endsAtEpochMs)
        validateEventTarget(categoryTreeId, categoryId, taskId, requireActive = true)
        val row = PlannedEventRow(Uuid7.new(now), workspaceId, planId, categoryTreeId, categoryId, taskId, at, updatedAtEpochMs = now)
        dao.putPlannedEvent(row); dao.enqueue(outboxForPlannedEvent(row))
    }

    suspend fun updatePlannedEvent(id: String, categoryTreeId: String, categoryId: String?, taskId: String?, at: Long, now: Long = System.currentTimeMillis()) = database.withTransaction {
        val current = requireNotNull(dao.plannedEvent(id)); val plan = requireNotNull(dao.plan(current.planId))
        require(current.workspaceId == workspaceId && current.deletedAtEpochMs == null && plan.workspaceId == workspaceId && !plan.archived)
        require(at >= plan.startsAtEpochMs && at < plan.endsAtEpochMs)
        val historicalTarget = current.categoryTreeId == categoryTreeId && current.categoryId == categoryId
        validateEventTarget(categoryTreeId, categoryId, taskId, requireActive = !historicalTarget || taskId != null && taskId != current.taskId)
        val updated = current.copy(categoryTreeId = categoryTreeId, categoryId = categoryId, taskId = taskId,
            occurredAtEpochMs = at, updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
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
    private suspend fun closeTaskInterval(task: TaskRow, now: Long, groupId: String?, dependsOnGroupId: String? = null) {
        val category = effectiveCategory(task) ?: return
        val latest = dao.latestEventInTree(category.categoryTreeId) ?: return
        if (latest.taskId != task.id || latest.occurredAtEpochMs > now) return
        val stopAt = now
        val event = EventRow(Uuid7.new(stopAt + 1), workspaceId, category.categoryTreeId, category.id, null,
            stopAt, ZoneId.systemDefault().id, "manual", updatedAtEpochMs = stopAt)
        dao.putEvent(event); dao.enqueue(outboxForEvent(event, groupId).copy(dependsOnGroupId = dependsOnGroupId))
    }
    private suspend fun pauseTasksForCategory(categoryId: String, now: Long, dependsOnGroupId: String? = null) {
        dao.tasksForCategory(categoryId).filter { it.status == "active" }.forEach { task ->
            val paused = task.copy(status = "paused", updatedAtEpochMs = now, syncState = LocalSyncState.PENDING)
            val groupId = if (task.revision > 0) Uuid7.new(now) else null
            closeTaskInterval(task, now, groupId, dependsOnGroupId)
            dao.putTask(paused); replacePending("task", task.id, outboxForTask(paused, groupId).copy(dependsOnGroupId = dependsOnGroupId))
        }
    }
    private suspend fun validateEventTarget(treeId: String, categoryId: String?, taskId: String?, requireActive: Boolean = false) {
        require(dao.categoryTree(treeId)?.let { it.workspaceId == workspaceId && it.deletedAtEpochMs == null && (!requireActive || !it.archived && it.purgedAtEpochMs == null) } == true)
        categoryId?.let { require(dao.category(it)?.let { category -> category.workspaceId == workspaceId && category.categoryTreeId == treeId && category.deletedAtEpochMs == null && (!requireActive || !category.archived) } == true) }
        if (requireActive && categoryId != null) requireActiveCategory(categoryId)
        taskId?.let { require(dao.task(it)?.let { task -> task.workspaceId == workspaceId && task.deletedAtEpochMs == null && effectiveCategory(task)?.id == categoryId } == true) }
    }
    private suspend fun requireActiveCategory(id: String) {
        val category = requireNotNull(dao.category(id))
        require(category.workspaceId == workspaceId && !category.archived && category.deletedAtEpochMs == null)
        val tree = requireNotNull(dao.categoryTree(category.categoryTreeId))
        require(tree.workspaceId == workspaceId && !tree.archived && tree.purgedAtEpochMs == null && tree.deletedAtEpochMs == null)
        val visited = mutableSetOf(category.id)
        var parentId = category.parentId
        while (parentId != null) {
            require(visited.add(parentId)) { "Category parent cycle" }
            val parent = requireNotNull(dao.category(parentId))
            require(parent.workspaceId == workspaceId && parent.categoryTreeId == category.categoryTreeId && !parent.archived && parent.deletedAtEpochMs == null)
            parentId = parent.parentId
        }
    }
    private suspend fun replacePending(type: String, id: String, row: OutboxRow) {
        require(!dao.hasPendingAtomicForEntity(type, id)) { "An atomic task transition is awaiting sync." }
        dao.deletePendingForEntity(type, id); dao.enqueue(row)
    }
    private suspend fun createCategoryTreeInternal(name: String, role: String, categoryNames: List<String>, now: Long) {
        val tree = CategoryTreeRow(Uuid7.new(now), workspaceId, name, role, updatedAtEpochMs = now)
        dao.putCategoryTree(tree); dao.enqueue(outboxForCategoryTree(tree))
        categoryNames.forEachIndexed { index, value ->
            val row = CategoryRow(Uuid7.new(now + index + 1), workspaceId, tree.id, name = value, sortOrder = index, updatedAtEpochMs = now)
            dao.putCategories(listOf(row)); dao.enqueue(outboxForCategory(row))
        }
    }

    private fun outbox(type: String, id: String, revision: Long, payload: JsonObject, now: Long, operation: String = "upsert", groupId: String? = null) =
        OutboxRow(Uuid7.new(now), clientId, workspaceId, type, id, operation, revision, payload.toString(), now, atomicGroupId = groupId)
    private fun outboxForCategoryTree(r: CategoryTreeRow) = outbox("categoryTree", r.id, r.revision, buildJsonObject { put("name", r.name); put("role", r.role); put("sortOrder", r.sortOrder); put("archived", r.archived) }, r.updatedAtEpochMs)
    private fun outboxForTreeAppearance(r: TreeAppearanceRow) = outbox("treeAppearance", r.id, r.revision, buildJsonObject { put("categoryTreeId", r.categoryTreeId); put("colorHex", r.colorHex) }, r.updatedAtEpochMs)
    private fun outboxForPalette(r: PaletteRow) = outbox("palette", r.id, r.revision, buildJsonObject {
        put("name", r.name); put("categoryColorsJson", r.categoryColorsJson); put("itemOrderJson", r.itemOrderJson); put("rowsJson", r.rowsJson); put("archived", r.archived)
    }, r.updatedAtEpochMs)
    private fun outboxForCategory(r: CategoryRow) = outbox("category", r.id, r.revision, buildJsonObject { put("categoryTreeId", r.categoryTreeId); putNullable("parentId", r.parentId); put("name", r.name); put("loadType", r.loadType); put("sortOrder", r.sortOrder); put("archived", r.archived) }, r.updatedAtEpochMs)
    private fun outboxForEvent(r: EventRow, groupId: String? = null) = outbox("event", r.id, r.revision, buildJsonObject { put("categoryTreeId", r.categoryTreeId); putNullable("categoryId", r.categoryId); putNullable("taskId", r.taskId); put("occurredAt", Instant.ofEpochMilli(r.occurredAtEpochMs).toString()); put("zoneId", r.zoneId); put("source", r.source); putNullable("note", r.note) }, r.updatedAtEpochMs, groupId = groupId)
    private fun outboxForTask(r: TaskRow, groupId: String? = null) = outbox("task", r.id, r.revision, buildJsonObject { put("title", r.title); putNullable("categoryId", r.categoryId); putNullable("parentTaskId", r.parentTaskId); put("estimateMinutes", r.estimateMinutes); r.deadlineEpochMs?.let { put("deadline", Instant.ofEpochMilli(it).toString()) }; r.nextActionDateEpochDay?.let { put("nextActionDate", LocalDate.ofEpochDay(it).toString()) }; r.nextActionMinuteOfDay?.let { put("nextActionTime", "%02d:%02d:00".format(it / 60, it % 60)) }; put("zoneId", r.zoneId); put("value", r.value); put("energy", r.energy); put("progress", r.progress); put("status", r.status); put("splittable", r.splittable); put("sortOrder", r.sortOrder) }, r.updatedAtEpochMs, groupId = groupId)
    private fun outboxForTaskComment(r: TaskCommentRow) = outbox("taskComment", r.id, r.revision, buildJsonObject { put("taskId", r.taskId); put("authorId", r.authorId); put("text", r.text) }, r.updatedAtEpochMs)
    private fun outboxForPlan(r: PlanRow) = outbox("plan", r.id, r.revision, buildJsonObject { put("name", r.name); put("startsAt", Instant.ofEpochMilli(r.startsAtEpochMs).toString()); put("endsAt", Instant.ofEpochMilli(r.endsAtEpochMs).toString()); put("zoneId", r.zoneId); put("archived", r.archived) }, r.updatedAtEpochMs)
    private fun outboxForBudget(r: BudgetAllocationRow) = outbox("budgetAllocation", r.id, r.revision, buildJsonObject { put("planId", r.planId); put("categoryId", r.categoryId); put("ownMinutes", r.ownMinutes) }, r.updatedAtEpochMs)
    private fun outboxForPlannedEvent(r: PlannedEventRow) = outbox("plannedEvent", r.id, r.revision, buildJsonObject { put("planId", r.planId); put("categoryTreeId", r.categoryTreeId); putNullable("categoryId", r.categoryId); putNullable("taskId", r.taskId); put("occurredAt", Instant.ofEpochMilli(r.occurredAtEpochMs).toString()); putNullable("note", r.note) }, r.updatedAtEpochMs)
    private fun JsonObjectBuilder.putNullable(name: String, value: String?) { put(name, value?.let(::JsonPrimitive) ?: JsonNull) }

    companion object {
        const val DEFAULT_WORKSPACE_ID = "018f0000-0000-7000-8000-000000000002"
        const val DEVELOPMENT_USER_ID = "018f0000-0000-7000-8000-000000000001"
    }
}
