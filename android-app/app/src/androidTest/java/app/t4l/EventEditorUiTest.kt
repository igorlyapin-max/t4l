package app.t4l

import androidx.activity.ComponentActivity
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.t4l.data.CategoryRow
import app.t4l.data.CategoryTreeRow
import app.t4l.data.DashboardState
import app.t4l.data.TaskRow
import app.t4l.ui.theme.T4LTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.After

class EventEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun clearWindowFlags() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(false)
            activity.setTurnScreenOn(false)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Test
    fun editingCanChangeCategoryAndLinkCompletedTaskWithoutChangingTime() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val tree = CategoryTreeRow("tree", "workspace", "Activity", updatedAtEpochMs = 1)
        val state = DashboardState(
            categoryTrees = listOf(tree), historicalCategoryTrees = listOf(tree),
            categories = listOf(
                CategoryRow("work", "workspace", "tree", name = "Work", updatedAtEpochMs = 1),
                CategoryRow("rest", "workspace", "tree", name = "Rest", updatedAtEpochMs = 1),
            ),
        )
        val task = TaskRow("task", "workspace", "Rest task", "rest", estimateMinutes = 15, status = "completed", zoneId = "UTC", updatedAtEpochMs = 1)
        var saved: List<Any?>? = null
        compose.setContent { T4LTheme {
            EventDialog(state, listOf(task), 1_000, initialTreeId = "tree", initialCategoryId = "work", editing = true,
                onDismiss = {}) { treeId, categoryId, taskId, at, done ->
                saved = listOf(treeId, categoryId, taskId, at)
                done(true)
            }
        } }

        compose.onNodeWithText("${compose.activity.getString(R.string.category)}: Work").performClick()
        compose.onNodeWithText("Rest").performClick()
        compose.onNodeWithText("${compose.activity.getString(R.string.task)}: ${compose.activity.getString(R.string.no_task)}").performClick()
        compose.onNodeWithText("Rest task · ${compose.activity.getString(R.string.status_completed)}").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.save)).performClick()

        compose.runOnIdle { assertEquals(listOf("tree", "rest", "task", 1_000L), saved) }
    }

    @Test
    fun archivedPlannedEventKeepsItsOriginalCategorySelectable() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val tree = CategoryTreeRow("tree", "workspace", "Activity", archived = true, updatedAtEpochMs = 1)
        val state = DashboardState(
            historicalCategoryTrees = listOf(tree),
            categories = listOf(CategoryRow("work", "workspace", "tree", name = "Work", archived = true, updatedAtEpochMs = 1)),
        )
        var saved = false
        compose.setContent { T4LTheme {
            EventDialog(state, emptyList(), 2_000, 1_000L..5_000L,
                initialTreeId = "tree", initialCategoryId = "work", editing = true, onDismiss = {}) { _, _, _, _, done ->
                saved = true
                done(true)
            }
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.save)).performClick()
        compose.runOnIdle { assertEquals(true, saved) }
    }
}
