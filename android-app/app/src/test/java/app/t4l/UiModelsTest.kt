package app.t4l

import app.t4l.data.CategoryRow
import app.t4l.data.ConflictRow
import app.t4l.data.TaskRow
import app.t4l.data.HttpStatusException
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

    private fun category(id: String, parentId: String?, sortOrder: Int) =
        CategoryRow(id, "w", "tree", parentId, id, sortOrder = sortOrder, updatedAtEpochMs = 1)

    private fun task(id: String, status: String, parentId: String) = TaskRow(
        id = id,
        workspaceId = "w",
        title = id.substringAfter('-'),
        categoryId = null,
        parentTaskId = parentId,
        estimateMinutes = 10,
        remainingEstimateMinutes = 10,
        zoneId = "UTC",
        status = status,
        updatedAtEpochMs = 1,
    )
}
