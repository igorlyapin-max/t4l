package app.t4l

import androidx.activity.ComponentActivity
import android.view.WindowManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.t4l.data.TaskRow
import app.t4l.ui.theme.T4LTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun profileAvatarExposesAccessibleName() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        compose.setContent { T4LTheme { ProfileAvatar(null, Modifier, "Open profile") } }

        compose.onNodeWithContentDescription("Open profile")
            .assertContentDescriptionEquals("Open profile")
    }

    @Test
    fun eventTaskLinkOpensResolvedTask() {
        var openedTask: String? = null
        val task = task("child", "Linked task", "active", null)
        compose.setContent { T4LTheme { EventTaskLink(task.id, listOf(task)) { openedTask = it } } }
        val label = compose.activity.getString(R.string.event_task, task.title)

        compose.onNodeWithText(label).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(task.id, openedTask) }
    }

    @Test
    fun subtaskSectionExpandsInStatusOrderAndOpensChild() {
        var openedTask: String? = null
        val tasks = listOf(
            task("done", "Done", "completed", "parent"),
            task("active", "Active", "active", "parent"),
            task("paused", "Paused", "paused", "parent"),
            task("cancelled", "Cancelled", "cancelled", "parent"),
        )
        compose.setContent { T4LTheme { SubtaskSection("parent", tasks) { openedTask = it } } }
        val expand = compose.activity.getString(R.string.view_subtasks)

        compose.onNodeWithText("Active").assertDoesNotExist()
        compose.onNodeWithText(expand).performClick()
        listOf("Active", "Paused", "Done", "Cancelled").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Paused").performClick()
        compose.runOnIdle { assertEquals("paused", openedTask) }
    }

    @Test
    fun emptySelectionIsStableAndDisabled() {
        compose.setContent { T4LTheme { SelectionMenu("Category", "missing", emptyList<String>(), { it }) {} } }

        compose.onNodeWithText("Category: —").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun missingSelectionMovesToFirstAvailableOption() {
        var selected = "missing"
        compose.setContent { T4LTheme { SelectionMenu("Category", selected, listOf("first", "second"), { it }) { selected = it } } }

        compose.waitForIdle()
        assertEquals("first", selected)
    }

    private fun task(id: String, title: String, status: String, parentId: String?) = TaskRow(
        id = id,
        workspaceId = "w",
        title = title,
        categoryId = null,
        parentTaskId = parentId,
        estimateMinutes = 10,
        remainingEstimateMinutes = 10,
        zoneId = "UTC",
        status = status,
        updatedAtEpochMs = 1,
    )
}
