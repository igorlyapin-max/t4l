package app.t4l.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMergeTest {
    @Test fun changedFieldsContainOnlyEditedValues() {
        assertEquals(setOf("lifeExpectancyYears"), profileChangedFields(10, 70.0, 10, 71.0))
    }

    @Test fun disjointServerChangeCanRebase() {
        assertTrue(canRebaseProfile(setOf("lifeExpectancyYears"), 10, 70.0, 11, 70.0))
    }

    @Test fun overlappingServerChangeRequiresConflict() {
        assertFalse(canRebaseProfile(setOf("lifeExpectancyYears"), 10, 70.0, 10, 72.0))
    }
}
