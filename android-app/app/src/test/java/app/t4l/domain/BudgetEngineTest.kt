package app.t4l.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BudgetEngineTest {
    @Test fun totalsOwnAndAllDescendants() {
        val parents = mapOf("math" to null, "geometry" to "math", "algebra" to "math", "linear" to "algebra")
        val own = mapOf("math" to 15, "geometry" to 30, "algebra" to 20, "linear" to 10)
        assertEquals(75, BudgetEngine.totalMinutes("math", parents, own))
        assertEquals(30, BudgetEngine.totalMinutes("algebra", parents, own))
    }

    @Test fun rejectsCategoryCycles() {
        assertThrows(IllegalArgumentException::class.java) {
            BudgetEngine.totalMinutes("a", mapOf("a" to "b", "b" to "a"), emptyMap())
        }
    }
}
