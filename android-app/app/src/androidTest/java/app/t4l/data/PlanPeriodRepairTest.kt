package app.t4l.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanPeriodRepairTest {
    @Test
    fun malformedPullCannotReplaceValidPlanWithEpochZero() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, T4LDatabase::class.java).build()
        try {
            val dao = database.dao()
            val workspace = "workspace"
            val valid = PlanRow("plan", workspace, "Day", 1_700_000_000_000, 1_700_003_600_000, "UTC", revision = 1, updatedAtEpochMs = 1)
            dao.putPlan(valid)
            val malformed = Json.parseToJsonElement("""{"name":"Day","startsAt":"bad","endsAt":null}""").jsonObject
            val failed = runCatching { applyApiChange(dao, workspace, ApiChange(2, "plan", "plan", 2, false, malformed)) }
            assertEquals(true, failed.exceptionOrNull() is IllegalArgumentException)
            assertEquals(valid, dao.plan("plan"))
        } finally { database.close() }
    }

    @Test
    fun repairValidatesPeriodAndQueuesPlanWithoutChangingEventsOrBudget() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, T4LDatabase::class.java).build()
        try {
            val identity = DeviceIdentity(context)
            val workspace = identity.workspaceId
            val dao = database.dao()
            val broken = PlanRow("broken", workspace, "Old plan", 1_000, 1_000, "UTC", updatedAtEpochMs = 1)
            val budget = BudgetAllocationRow("budget", workspace, "broken", "category", 30, updatedAtEpochMs = 1)
            val event = PlannedEventRow("event", workspace, "broken", "tree", "category", null, 500, updatedAtEpochMs = 1)
            dao.putPlan(broken)
            dao.putBudgetAllocation(budget)
            dao.putPlannedEvent(event)
            val repository = T4LRepository(database, identity, SyncStateStore())

            val invalid = runCatching { repository.updatePlanPeriod("broken", 1_000, 1_000, 2) }
            assertEquals(true, invalid.exceptionOrNull() is IllegalArgumentException)
            assertEquals(broken, dao.plan("broken"))
            assertEquals(0, dao.pendingMutations(workspace).size)

            repository.updatePlanPeriod("broken", 1_000, 61_000, 3)

            val repaired = requireNotNull(dao.plan("broken"))
            assertEquals(1_000L, repaired.startsAtEpochMs)
            assertEquals(61_000L, repaired.endsAtEpochMs)
            assertEquals(LocalSyncState.PENDING, repaired.syncState)
            assertEquals(budget, dao.observeBudgetAllocations(workspace).first().single())
            assertEquals(event, dao.observePlannedEvents(workspace).first().single())
            val mutation = dao.pendingMutations(workspace).single()
            assertEquals("plan", mutation.entityType)
            assertEquals("broken", mutation.entityId)
            assertEquals(true, mutation.payloadJson.contains("1970-01-01T00:01:01Z"))
        } finally {
            database.close()
        }
    }
}
