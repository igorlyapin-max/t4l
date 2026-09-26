package app.t4l

import androidx.activity.ComponentActivity
import android.view.WindowManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.RootMatchers.isDialog
import app.t4l.data.CategoryRow
import app.t4l.data.CategoryPlacement
import app.t4l.data.PlanRow
import app.t4l.data.DashboardState
import app.t4l.data.TaskRow
import app.t4l.data.TaskState
import app.t4l.data.SyncUiState
import app.t4l.ui.theme.T4LTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun invalidPlanPeriodDialogCanBeCancelledWithoutSaving() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        var dismissed = false
        var saved = false
        val plan = PlanRow("broken", "workspace", "Broken plan", 0, 0, "UTC", updatedAtEpochMs = 1)
        compose.setContent { T4LTheme { PlanPeriodDialog(plan, { dismissed = true }) { _, _ -> saved = true } } }

        compose.onNodeWithText(compose.activity.getString(R.string.repair_plan_period)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle {
            assertEquals(true, dismissed)
            assertEquals(false, saved)
        }
    }

    @Test
    fun invalidPlanPeriodDialogOffersValidUnsavedDraft() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        var saved: Pair<Long, Long>? = null
        val plan = PlanRow("broken", "workspace", "Broken plan", 0, 0, "UTC", updatedAtEpochMs = 1)
        compose.setContent { T4LTheme { PlanPeriodDialog(plan, {}) { start, end -> saved = start to end } } }

        compose.onNodeWithText(compose.activity.getString(R.string.save)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, requireNotNull(saved).second > requireNotNull(saved).first) }
    }

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

    @Test
    fun headerCountdownUsesAccessiblePlaceholderWhenProfileIsIncomplete() {
        compose.setContent { T4LTheme { HeaderLifeCountdown(null, {}) } }

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.complete_profile_for_countdown))
            .assertIsDisplayed()
    }

    @Test
    fun syncIndicatorOpensDiagnosticsAndExposesTextStatus() {
        var opened = false
        compose.setContent { T4LTheme { SyncStatusIndicator(SyncUiState(), onClick = { opened = true }) } }
        val description = compose.activity.getString(
            R.string.open_sync_diagnostics,
            compose.activity.getString(R.string.sync_ok),
        )

        compose.onNodeWithContentDescription(description).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, opened) }
    }

    @Test
    fun timerIconControlsExposeLocalizedActions() {
        var startClicked = false
        compose.setContent {
            T4LTheme {
                TimerControlButtons(
                    state = PomodoroState(),
                    onStartPause = { startClicked = true },
                    onReset = {},
                    onSkip = {},
                )
            }
        }

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.start)).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.reset)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.skip)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(true, startClicked) }
    }

    @Test
    fun runningTimerUsesPauseAction() {
        compose.setContent {
            T4LTheme {
                TimerControlButtons(PomodoroState(status = PomodoroStatus.RUNNING), {}, {}, {})
            }
        }

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.pause)).assertIsDisplayed()
    }

    @Test
    fun taskToolbarOpensStatusViewSortAndDetailSettings() {
        val expanded = mutableStateOf(false)
        compose.setContent {
            T4LTheme {
                TaskListToolbar(
                    settings = TaskListSettings(),
                    expanded = expanded.value,
                    canAdd = true,
                    onExpandedChange = { expanded.value = it },
                    onSettingsChange = {},
                    onAdd = {},
                )
            }
        }

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.task_list_settings)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.task_statuses)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.task_view_tree)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.sort_priority)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.details_all)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun taskTitleTapOpensEditorAndRenameHasAccessibleAction() {
        val row = task("task", "Example task", "active", null)
        var editOpened = false
        compose.setContent {
            T4LTheme {
                TaskListRow(
                    task = row,
                    depth = 0,
                    state = DashboardState(),
                    taskState = TaskState(tasks = listOf(row)),
                    detailMode = TaskDetailMode.TITLE,
                    revealed = false,
                    dragging = false,
                    dropTarget = false,
                    onBounds = {},
                    onReveal = {},
                    onClose = {},
                    onDismissOther = { false },
                    onRename = {},
                    onEdit = { editOpened = true },
                    onStatus = {},
                    onDragStart = {},
                    onDrag = {},
                    onDragEnd = {},
                    onMoveEarlier = {},
                    onMoveLater = {},
                )
            }
        }

        compose.onNodeWithText(row.title).performClick()
        compose.runOnIdle { assertEquals(true, editOpened) }
        val renameLabel = compose.activity.getString(R.string.rename)
        compose.onNodeWithText(row.title).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .first { it.label == renameLabel }.action()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).fetchSemanticsNode()
    }

    @Test
    fun optionalDateFieldCanBeClearedWithoutOpeningPicker() {
        val date = mutableStateOf("2026-09-23")
        compose.setContent {
            T4LTheme {
                DateField(date.value, "Next step", { date.value = it }, optional = true)
            }
        }

        compose.onNodeWithText(compose.activity.getString(R.string.clear)).performClick()
        compose.runOnIdle { assertEquals("", date.value) }
        compose.onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    fun nextActionQuickDateChangesOnlyFieldDraft() {
        val date = mutableStateOf("")
        compose.setContent { T4LTheme { DateField(date.value, "Next step", { date.value = it }, presets = DatePresets.NEXT_ACTION) } }

        compose.onNodeWithText("—").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.picker_tomorrow)).performClick()
        compose.runOnIdle { assertEquals(java.time.LocalDate.now().plusDays(1).toString(), date.value) }
    }

    @Test
    fun cancellingDateTimeEditorKeepsOriginalDraft() {
        val value = mutableStateOf("")
        compose.setContent { T4LTheme { DateTimeField(value.value, "Date and time", { value.value = it }, presets = DateTimePresets.DEADLINE) } }

        compose.onNodeWithText("—").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals("", value.value) }
    }

    @Test
    fun openDistributionEndPresetClearsDate() {
        val date = mutableStateOf("2026-09-23")
        compose.setContent { T4LTheme { DateField(date.value, "End", { date.value = it }, optional = true, presets = DatePresets.DISTRIBUTION_END) } }

        val displayed = java.time.LocalDate.parse(date.value).format(
            java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .withLocale(compose.activity.resources.configuration.locales[0]),
        )
        compose.onNodeWithText(displayed).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.picker_open_end)).performClick()
        compose.runOnIdle { assertEquals("", date.value) }
    }

    @Test
    fun exactDateAndTimeCanBeChosenIndependentlyBeforeDone() {
        val value = mutableStateOf("2026-09-24 09:00")
        compose.setContent { T4LTheme { DateTimeField(value.value, "Date and time", { value.value = it }) } }
        val displayed = java.time.LocalDateTime.parse(value.value, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
                .withLocale(compose.activity.resources.configuration.locales[0]))

        compose.onNodeWithText(displayed).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.picker_choose_date)).performClick()
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        compose.onNodeWithText(compose.activity.getString(R.string.picker_choose_time)).assertIsDisplayed().performClick()
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
        compose.onNodeWithText(compose.activity.getString(R.string.picker_done)).performClick()
        compose.runOnIdle { assertEquals("2026-09-24 09:00", value.value) }
    }

    @Test
    fun planStartQuickPresetKeepsDefaultEndDuration() {
        var saved: Pair<Long, Long>? = null
        compose.setContent { T4LTheme { PlanDialog({}, { _, start, end -> saved = start to end }) } }

        val startLabel = compose.activity.getString(R.string.plan_start)
        compose.onNode(hasContentDescription(startLabel, substring = true)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.picker_tomorrow)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Plan")
        compose.onNodeWithText(compose.activity.getString(R.string.save)).performClick()
        compose.runOnIdle { assertEquals(24 * 60 * 60 * 1000L, requireNotNull(saved).second - requireNotNull(saved).first) }
    }

    @Test
    fun openDateTimeEditorRestoresWithoutCommittingDraft() {
        val value = mutableStateOf("")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { T4LTheme { DateTimeField(value.value, "Date and time", { value.value = it }, presets = DateTimePresets.DEADLINE) } }

        compose.onNodeWithText("—").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(compose.activity.getString(R.string.picker_choose_date)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals("", value.value) }
    }

    @Test
    fun categoryTreeRowOpensAndSwipeRevealsActionsWithoutDeleting() {
        var opened = false
        var renamed = false
        var deleted = false
        var revealed = false
        val name = "Activity"
        compose.setContent {
            T4LTheme {
                CategoryTreeListItem(
                    name = name,
                    revealed = revealed,
                    onRevealed = { revealed = true },
                    onCloseActions = { revealed = false },
                    onOpen = { opened = true },
                    onRename = { renamed = true },
                    onDelete = { deleted = true },
                )
            }
        }

        compose.onNodeWithContentDescription(name).performClick()
        compose.runOnIdle { assertEquals(true, opened) }
        compose.onNodeWithContentDescription(name).performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 5_000) { revealed }
        compose.runOnIdle { assertEquals(false, deleted) }
        compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.rename_tree_named, name),
        ).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, renamed) }
    }

    @Test
    fun categoryTreeRowLongPressProvidesDeleteAlternative() {
        var deleted = false
        val name = "Location"
        compose.setContent {
            T4LTheme {
                CategoryTreeListItem(name, false, {}, {}, {}, {}, { deleted = true })
            }
        }

        compose.onNodeWithContentDescription(name).performTouchInput { longClick() }
        compose.onAllNodesWithText(compose.activity.getString(R.string.delete))[1].assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, deleted) }
    }

    @Test
    fun categoryTreeEditorShowsStructureWithoutTrackingSelection() {
        val categories = listOf(
            category("root", null, "Work"),
            category("child", "root", "Application"),
        )
        compose.setContent {
            T4LTheme {
                CategoryTreeEditor("Activity", categories, PaddingValues(), { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
            }
        }

        compose.onNodeWithText("Activity").assertIsDisplayed()
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Application").assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.add_root_category)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.unknown)).assertDoesNotExist()
    }

    @Test
    fun categorySelectionChangesPlusToChildCreationAndDoubleTapRenames() {
        val categories = listOf(category("root", null, "Work"))
        compose.setContent {
            T4LTheme {
                CategoryTreeEditor("Activity", categories, PaddingValues(), { _, _ -> }, { _, _ -> }, { _, _ -> }, {})
            }
        }

        compose.onNodeWithText("Work").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.add_child_category, "Work"),
        ).assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.add_category)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()

        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.add_root_category)).assertIsDisplayed()
        compose.onNodeWithText("Work").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Work").performTouchInput { doubleClick() }
        compose.onNodeWithText(compose.activity.getString(R.string.rename)).assertIsDisplayed()
    }

    @Test
    fun categoryMoveMenuProvidesNonGestureAlternative() {
        val categories = listOf(
            category("root", null, "Work"),
            category("other", null, "Rest"),
        )
        var moved: Pair<String, CategoryPlacement>? = null
        compose.setContent {
            T4LTheme {
                CategoryTreeEditor(
                    "Activity",
                    categories,
                    PaddingValues(),
                    { _, _ -> },
                    { id, placement -> moved = id to placement },
                    { _, _ -> },
                    {},
                )
            }
        }

        compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.category_actions, "Work"),
        ).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil {
            compose.onAllNodesWithText(compose.activity.getString(R.string.move_category))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.move_category)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.move_to_root), substring = true).assertIsDisplayed()
        compose.onAllNodesWithText(compose.activity.getString(R.string.move_category))[0].performClick()

        compose.runOnIdle { assertEquals("root" to CategoryPlacement.Root, moved) }
    }

    @Test
    fun categoryDoubleTapDragMovesIntoTarget() {
        val categories = listOf(
            category("root", null, "Work"),
            category("target", null, "Rest"),
        )
        var moved: Pair<String, CategoryPlacement>? = null
        compose.setContent {
            T4LTheme {
                CategoryTreeEditor(
                    "Activity",
                    categories,
                    PaddingValues(),
                    { _, _ -> },
                    { id, placement -> moved = id to placement },
                    { _, _ -> },
                    {},
                )
            }
        }

        val action = compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.category_actions, "Work"),
        )
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        val targetCenter = compose.onNodeWithText("Rest").fetchSemanticsNode().boundsInRoot.center
        action.performTouchInput {
            down(center)
            up()
            advanceEventTime(100)
            down(center)
            advanceEventTime(100)
            moveTo(targetCenter - actionBounds.topLeft, delayMillis = 300)
            up()
        }

        compose.waitUntil { moved != null }
        compose.runOnIdle { assertEquals("root" to CategoryPlacement.Inside("target"), moved) }
    }

    private fun task(id: String, title: String, status: String, parentId: String?) = TaskRow(
        id = id,
        workspaceId = "w",
        title = title,
        categoryId = null,
        parentTaskId = parentId,
        estimateMinutes = 10,
        zoneId = "UTC",
        status = status,
        updatedAtEpochMs = 1,
    )

    private fun category(id: String, parentId: String?, name: String) = CategoryRow(
        id = id,
        workspaceId = "w",
        categoryTreeId = "tree",
        parentId = parentId,
        name = name,
        updatedAtEpochMs = 1,
    )
}
