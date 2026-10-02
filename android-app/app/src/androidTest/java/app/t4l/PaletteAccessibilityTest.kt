package app.t4l

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.t4l.ui.theme.T4LTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PaletteAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun colorSwatchHasNameSelectionAndTouchTarget() {
        compose.setContent { T4LTheme { HexColorDialog(null, {}, onSave = {}) } }
        val swatch = compose.onNodeWithContentDescription("#D32F2F")
        swatch.assertIsDisplayed().performClick().assertIsSelected()
        val minimumPx = 48f * compose.activity.resources.displayMetrics.density
        val bounds = swatch.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= minimumPx - 1f)
        assertTrue(bounds.height >= minimumPx - 1f)
    }
}
