package app.t4l

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDestinationSettingsTest {
    @Test
    fun missingOrUnknownSettingKeepsTomatoAsHome() {
        assertEquals(HomeDestination.TOMATO, HomeDestination.fromStored(null))
        assertEquals(HomeDestination.TOMATO, HomeDestination.fromStored("unsupported"))
    }

    @Test
    fun everyCandidateHidesOnlyItsOwnMenuEntry() {
        val candidates = HomeDestination.entries
        assertEquals(6, candidates.size)
        candidates.forEach { selected ->
            assertFalse(isVisibleMainMenuRoute(selected.route, selected))
            assertTrue(isVisibleMainMenuRoute("home", selected))
            assertTrue(isVisibleMainMenuRoute("settings", selected))
            candidates.filterNot { it == selected }.forEach { other ->
                assertTrue(isVisibleMainMenuRoute(other.route, selected))
            }
        }
    }
}
