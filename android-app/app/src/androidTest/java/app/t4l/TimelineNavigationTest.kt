package app.t4l

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import org.junit.Test

class TimelineNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var originalHome: HomeDestination? = null
    private var originalPlan: String? = null
    private var originalPanelFraction: Float? = null
    private var originalPanelCollapsed: Boolean? = null
    private var workspaceId: String? = null

    @After fun restorePreferences() {
        compose.activityRule.scenario.onActivity { activity ->
            val app = activity.application as T4LApplication
            originalHome?.let(app.homeDestinationStore::update)
            workspaceId?.let {
                app.timelineUiStore.selectPlan(it, originalPlan)
                originalPanelFraction?.let { size -> app.timelineUiStore.setPanelFraction(it, "history", size) }
                originalPanelCollapsed?.let { collapsed -> app.timelineUiStore.setPanelCollapsed(it, "history", collapsed) }
            }
        }
    }

    @Test fun categorizationTrackingAndPlannerHaveTheirNewEntryPoints() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val app = activity.application as T4LApplication
            originalHome = app.homeDestinationStore.state.value
            app.homeDestinationStore.update(HomeDestination.TOMATO)
            workspaceId = app.repository.workspaceId
            originalPlan = app.timelineUiStore.selectedPlan(requireNotNull(workspaceId))
            originalPanelFraction = app.timelineUiStore.panelFraction(requireNotNull(workspaceId), "history")
            originalPanelCollapsed = app.timelineUiStore.panelCollapsed(requireNotNull(workspaceId), "history")
            app.timelineUiStore.selectPlan(requireNotNull(workspaceId), null)
            app.timelineUiStore.setPanelFraction(requireNotNull(workspaceId), "history", 0.5f)
            app.timelineUiStore.setPanelCollapsed(requireNotNull(workspaceId), "history", false)
        }
        compose.waitUntil(timeoutMillis = 30_000) {
            runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        compose.waitForIdle()
        val menu = compose.activity.getString(R.string.menu)
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.track)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.trees_label), substring = true).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.palettes_label)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.categorization_split)).assertIsDisplayed()

        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.history)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.distribution_label), substring = true).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.palette_label)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.palette_label)).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.palette_date), substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.planner)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.active_plans), substring = true).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.archived_plans)).assertIsDisplayed()
    }
}
