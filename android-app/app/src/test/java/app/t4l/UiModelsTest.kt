package app.t4l

import app.t4l.data.CategoryRow
import app.t4l.data.ConflictRow
import app.t4l.data.TaskRow
import app.t4l.data.HttpStatusException
import app.t4l.data.OutboxRow
import app.t4l.data.SyncPhase
import app.t4l.data.SyncRuntimeState
import app.t4l.data.SyncUiState
import app.t4l.domain.LifeCountdown
import java.io.IOException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModelsTest {
    @Test
    fun conflictPresentationShowsChangedUserFields() {
        val row = ConflictRow(
            clientMutationId = "mutation",
            workspaceId = "workspace",
            entityType = "task",
            entityId = "12345678-aaaa-bbbb-cccc-dddddddddddd",
            localPayloadJson = """{"title":"Local","status":"active","estimateMinutes":30}""",
            serverPayloadJson = """{"id":"ignored","title":"Server","status":"active","estimateMinutes":60,"revision":4}""",
            serverRevision = 4,
            createdAtEpochMs = 1,
        )

        val result = ConflictPresenter.present(row)

        assertEquals("Local", result.title)
        assertEquals(listOf("estimateMinutes", "title"), result.fields.map { it.key })
        assertFalse(result.fields.any { it.key == "revision" || it.key == "id" })
    }

    @Test
    fun conflictPresentationHandlesDeleteAndInvalidJson() {
        val row = ConflictRow("m", "w", "event", "12345678-rest", "{}", "{\"note\":\"server\"}", 2, 1, operation = "delete")
        assertEquals("note", ConflictPresenter.present(row).fields.single().key)
        assertTrue(ConflictPresenter.isJsonObject("{}"))
        assertFalse(ConflictPresenter.isJsonObject("[]"))
        assertFalse(ConflictPresenter.isJsonObject("broken"))
    }

    @Test
    fun deepTreeKeepsEveryLevelAndHonorsCollapsedParents() {
        val categories = (0 until 7).map { depth ->
            CategoryRow("c$depth", "w", "t", if (depth == 0) null else "c${depth - 1}", "Level $depth", updatedAtEpochMs = depth.toLong())
        }

        assertEquals((0 until 7).toList(), flattenVisibleCategories(categories, emptySet()).map { it.second })
        assertEquals(listOf("c0", "c1", "c2"), flattenVisibleCategories(categories, setOf("c2")).map { it.first.id })
    }

    @Test
    fun descendantLookupReturnsWholeSubtreeWithoutParent() {
        val categories = listOf(
            category("root", null, 0),
            category("child", "root", 0),
            category("grandchild", "child", 0),
            category("other", null, 1),
        )

        assertEquals(setOf("child", "grandchild"), descendantCategoryIds(categories, "root"))
    }

    @Test
    fun technicalErrorsMapToSafeUserCategories() {
        assertEquals(UiMessageKind.OFFLINE, UiErrorMapper.map(IOException("connection refused")))
        assertEquals(UiMessageKind.AUTHORIZATION, UiErrorMapper.map(HttpStatusException(401)))
        assertEquals(UiMessageKind.VALIDATION, UiErrorMapper.map(IllegalArgumentException("raw validation")))
        assertEquals(UiMessageKind.BACKUP_INVALID_FILE, UiErrorMapper.map(SerializationException("raw payload"), backup = true))
    }

    @Test
    fun compactBudgetTreeHidesOnlyRedundantNodesAndPromotesVisibleDescendants() {
        val categories = listOf(
            category("root", null, 0),
            category("branch", "root", 0),
            category("timed", "branch", 0),
            category("empty", "branch", 1),
            category("own", null, 1),
        )
        val own = mapOf("timed" to 30, "own" to 10)

        val compact = budgetCategoryItems(categories, own, hideRedundant = true)

        assertEquals(listOf("branch", "timed", "own"), compact.map { it.category.id })
        assertEquals(listOf(0, 1, 0), compact.map { it.depth })
        assertEquals(categories.map { it.id }, budgetCategoryItems(categories, own, hideRedundant = false).map { it.category.id })
    }

    @Test
    fun compactBudgetTreeKeepsCategoriesWithComputedTimelineTime() {
        val categories = listOf(category("root", null, 0), category("child", "root", 0))
        val compact = budgetCategoryItems(categories, emptyMap(), hideRedundant = true, timelineOwnMillis = mapOf("child" to 1000L))
        assertEquals(listOf("child"), compact.map { it.category.id })
        assertEquals(listOf(0), compact.map { it.depth })
    }

    @Test
    fun directSubtasksSortByStatusThenTitle() {
        val tasks = listOf(
            task("cancelled-z", "cancelled", "parent"),
            task("completed-z", "completed", "parent"),
            task("paused-z", "paused", "parent"),
            task("active-z", "active", "parent"),
            task("active-a", "active", "parent"),
            task("other", "active", "another-parent"),
        )

        assertEquals(
            listOf("active-a", "active-z", "paused-z", "completed-z", "cancelled-z"),
            directSubtasks("parent", tasks).map { it.id },
        )
    }

    @Test
    fun taskListSettingsFilterModesAndPromoteVisibleDescendants() {
        val tasks = listOf(
            task("root", "paused", null, sortOrder = 0),
            task("child", "active", "root", sortOrder = 20),
            task("second", "active", null, sortOrder = 10),
        )
        val activeTree = TaskListSettings(statuses = setOf("active"), listMode = TaskListMode.TREE, sortMode = TaskSortMode.PRIORITY)

        assertEquals(listOf("second", "child"), taskListItems(tasks, activeTree).map { it.task.id })
        assertEquals(listOf(0, 0), taskListItems(tasks, activeTree).map { it.depth })
        assertEquals(
            listOf("second"),
            taskListItems(tasks, activeTree.copy(listMode = TaskListMode.TOP_LEVEL)).map { it.task.id },
        )
        assertEquals(
            listOf("second", "child"),
            taskListItems(tasks, activeTree.copy(listMode = TaskListMode.ALL)).map { it.task.id },
        )
    }

    @Test
    fun taskListSupportsIndependentDateAndPrioritySorting() {
        val tasks = listOf(
            task("later-priority", "active", null, sortOrder = 20, nextDate = 1),
            task("first-priority", "active", null, sortOrder = 0, nextDate = 2),
        )

        assertEquals(listOf("first-priority", "later-priority"), tasks.sortedWith(taskComparator(TaskSortMode.PRIORITY)).map { it.id })
        assertEquals(listOf("later-priority", "first-priority"), tasks.sortedWith(taskComparator(TaskSortMode.NEXT_ACTION)).map { it.id })
    }

    @Test
    fun reorderMovesTaskBeforeOrAfterTargetDeterministically() {
        val tasks = listOf(
            task("a", "active", null, sortOrder = 0),
            task("b", "active", null, sortOrder = 10),
            task("c", "active", null, sortOrder = 20),
        )

        assertEquals(listOf("c", "a", "b"), reorderedTaskIds(tasks, "c", "a", after = false))
        assertEquals(listOf("b", "c", "a"), reorderedTaskIds(tasks, "a", "c", after = true))
    }

    @Test
    fun visiblePriorityMovesPreserveHiddenTasksAndTreeSiblingBoundary() {
        val tasks = listOf(
            task("a", "active", null, sortOrder = 0),
            task("hidden", "paused", null, sortOrder = 10),
            task("b", "active", null, sortOrder = 20),
            task("child", "active", "b", sortOrder = 25),
            task("c", "active", null, sortOrder = 30),
        )
        val visible = taskListItems(tasks, TaskListSettings(statuses = setOf("active"), sortMode = TaskSortMode.PRIORITY))

        assertEquals("c", visibleMoveNeighbor(visible, 1, TaskListMode.TREE, 1)?.id)
        assertEquals(listOf("a", "hidden", "child", "c", "b"), reorderedTaskIds(tasks, "b", "c", after = true))
        assertFalse(validPriorityTarget(tasks[2], tasks[3], TaskListMode.TREE))
        assertTrue(validPriorityTarget(tasks[2], tasks[4], TaskListMode.TREE))
    }

    @Test
    fun compactCountdownUsesStableNumericFormatAndPlaceholder() {
        assertEquals("09-003-04-05-06", compactLifeCountdown(LifeCountdown(9, 3, 4, 5, 6)))
        assertEquals("-- --- -- -- --", compactLifeCountdown(null))
    }

    @Test
    fun syncIndicatorIsGreenOnlyWhenEverythingIsSynchronized() {
        assertEquals(SyncIndicatorState.HEALTHY, syncIndicatorState(SyncUiState()))
        assertEquals(
            SyncIndicatorState.NEEDS_ATTENTION,
            syncIndicatorState(SyncUiState(runtime = SyncRuntimeState(phase = SyncPhase.SYNCING))),
        )
        assertEquals(
            SyncIndicatorState.NEEDS_ATTENTION,
            syncIndicatorState(SyncUiState(runtime = SyncRuntimeState(phase = SyncPhase.RETRY))),
        )
        assertEquals(SyncIndicatorState.NEEDS_ATTENTION, syncIndicatorState(SyncUiState(pending = listOf(outbox()))))
        assertEquals(SyncIndicatorState.NEEDS_ATTENTION, syncIndicatorState(SyncUiState(), personalConflictCount = 1))
    }

    private fun category(id: String, parentId: String?, sortOrder: Int) =
        CategoryRow(id, "w", "tree", parentId, id, sortOrder = sortOrder, updatedAtEpochMs = 1)

    private fun task(id: String, status: String, parentId: String?, sortOrder: Int = 0, nextDate: Long? = null) = TaskRow(
        id = id,
        workspaceId = "w",
        title = id.substringAfter('-'),
        categoryId = null,
        parentTaskId = parentId,
        estimateMinutes = 10,
        zoneId = "UTC",
        status = status,
        sortOrder = sortOrder,
        nextActionDateEpochDay = nextDate,
        updatedAtEpochMs = 1,
    )

    private fun outbox() = OutboxRow("m", "client", "w", "task", "t", "upsert", 0, "{}", 1)
}
