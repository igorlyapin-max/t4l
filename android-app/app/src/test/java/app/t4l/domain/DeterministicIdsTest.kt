package app.t4l.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DeterministicIdsTest {
    @Test
    fun `budget allocation identity matches cross-platform contract`() {
        assertEquals(
            "97a22ba9-316f-5ff0-84ba-c5dad9395c8f",
            DeterministicIds.budgetAllocation(
                "018f0000-0000-7000-8000-000000000002",
                "01900000-0000-7000-8000-000000000014",
                "01900000-0000-7000-8000-000000000013",
            ),
        )
    }
}
