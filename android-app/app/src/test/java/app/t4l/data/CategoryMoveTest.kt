package app.t4l.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CategoryMoveTest {
    @Test
    fun movingInsideKeepsSubtreeAndNormalizesBothSiblingGroups() {
        val rows = listOf(
            category("root-a", null, 0),
            category("root-b", null, 1),
            category("child", "root-a", 0),
            category("grandchild", "child", 0),
        )

        val result = apply(rows, moveCategoryRows(rows, "child", CategoryPlacement.Inside("root-b"), 99))

        assertEquals("root-b", result.getValue("child").parentId)
        assertEquals("child", result.getValue("grandchild").parentId)
        assertEquals(0, result.getValue("child").sortOrder)
        assertEquals(LocalSyncState.PENDING, result.getValue("child").syncState)
    }

    @Test
    fun movingBeforeAncestorPromotesWholeSubtreeToAncestorsLevel() {
        val rows = listOf(
            category("root-a", null, 0),
            category("root-b", null, 10),
            category("child", "root-b", 0),
            category("grandchild", "child", 0),
        )

        val result = apply(rows, moveCategoryRows(rows, "child", CategoryPlacement.Before("root-b"), 99))

        assertEquals(null, result.getValue("child").parentId)
        assertEquals(listOf("root-a", "child", "root-b"), siblings(result.values, null))
        assertEquals("child", result.getValue("grandchild").parentId)
    }

    @Test
    fun rootPlacementAppendsCategoryAfterExistingRoots() {
        val rows = listOf(
            category("root-a", null, 0),
            category("root-b", null, 10),
            category("child", "root-a", 0),
        )

        val result = apply(rows, moveCategoryRows(rows, "child", CategoryPlacement.Root, 99))

        assertEquals(listOf("root-a", "root-b", "child"), siblings(result.values, null))
    }

    @Test
    fun movingIntoDescendantIsRejected() {
        val rows = listOf(
            category("root", null, 0),
            category("child", "root", 0),
            category("grandchild", "child", 0),
        )

        assertThrows(IllegalArgumentException::class.java) {
            moveCategoryRows(rows, "root", CategoryPlacement.Inside("grandchild"), 99)
        }
    }

    private fun apply(original: List<CategoryRow>, updates: List<CategoryRow>): Map<String, CategoryRow> =
        original.associateBy { it.id }.toMutableMap().apply { updates.forEach { put(it.id, it) } }

    private fun siblings(rows: Collection<CategoryRow>, parentId: String?): List<String> = rows
        .filter { it.parentId == parentId }
        .sortedBy { it.sortOrder }
        .map { it.id }

    private fun category(id: String, parentId: String?, sortOrder: Int) = CategoryRow(
        id = id,
        workspaceId = "workspace",
        categoryTreeId = "tree",
        parentId = parentId,
        name = id,
        sortOrder = sortOrder,
        updatedAtEpochMs = 1,
        syncState = LocalSyncState.SYNCED,
    )
}
