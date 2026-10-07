package app.t4l

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QuickClockAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun talkBackCanCreateExactlyOnePlannedEventWithoutVisibleConfirmButton() {
        var selected = 0
        compose.setContent {
            QuickClockDialog(LocalTime.of(9, 30), error = false, saving = false,
                onCancel = {}, onSelected = { _, _ -> selected++ })
        }
        compose.onNodeWithTag("quick_clock").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single().action()
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboardEnterCanCreateExactlyOnePlannedEvent() {
        var selected = 0
        compose.setContent {
            QuickClockDialog(LocalTime.of(9, 30), error = false, saving = false,
                onCancel = {}, onSelected = { _, _ -> selected++ })
        }
        compose.onNodeWithTag("quick_clock").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("quick_clock").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        compose.runOnIdle { assertEquals(1, selected) }
    }
}
