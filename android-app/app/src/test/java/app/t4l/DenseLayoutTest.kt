package app.t4l

import org.junit.Assert.assertEquals
import org.junit.Test

class DenseLayoutTest {
    @Test fun dividerRespectsBothPaneMinimums() {
        assertEquals(72f, boundedSplitPx(-100f, 500f, 72f, 72f))
        assertEquals(428f, boundedSplitPx(600f, 500f, 72f, 72f))
        assertEquals(210f, boundedSplitPx(210f, 500f, 72f, 72f))
    }

    @Test fun dividerIsBoundedOnShortScreens() {
        assertEquals(50f, boundedSplitPx(200f, 100f, 72f, 72f))
        assertEquals(0f, boundedSplitPx(200f, 0f, 72f, 72f))
    }
}
