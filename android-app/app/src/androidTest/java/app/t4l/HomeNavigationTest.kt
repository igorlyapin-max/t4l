package app.t4l

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var original: HomeDestination? = null

    @After
    fun restoreHomeSelection() {
        original?.let { destination ->
            compose.activityRule.scenario.onActivity { activity ->
                (activity.application as T4LApplication).homeDestinationStore.update(destination)
            }
        }
    }

    @Test
    fun selectedTasksAppearsOnHomeAndTomatoReturnsToMenu() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val store = (activity.application as T4LApplication).homeDestinationStore
            original = store.state.value
            store.update(HomeDestination.TOMATO)
        }

        val menu = compose.activity.getString(R.string.menu)
        val settings = compose.activity.getString(R.string.settings)
        val home = compose.activity.getString(R.string.home)
        val tasks = compose.activity.getString(R.string.tasks)
        val tomato = compose.activity.getString(R.string.pomodoro_timer)

        compose.onNodeWithText(tomato).assertIsDisplayed()
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(settings).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.home_screen)).performClick()
        compose.onNodeWithText(tasks).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.home_screen_current, tasks)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.back)).performClick()
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(home).performClick()

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.new_task)).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.new_task)).assertIsDisplayed()
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(tomato).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(HomeDestination.TASKS, (compose.activity.application as T4LApplication).homeDestinationStore.state.value)
            assertEquals(HomeDestination.TASKS, HomeDestinationStore(compose.activity).state.value)
        }

        compose.onNodeWithText(settings).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.home_screen)).performClick()
        compose.onNodeWithText(tomato).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.back)).performClick()
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(home).performClick()
        compose.onNodeWithText(tomato).assertIsDisplayed()
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(tasks).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(HomeDestination.TOMATO, HomeDestinationStore(compose.activity).state.value)
        }
    }
}
