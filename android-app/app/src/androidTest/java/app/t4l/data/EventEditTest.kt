package app.t4l.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventEditTest {
    @Test
    fun archivedTargetAllowsHistoricalPlannedTimeEditButNotNewReference() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, T4LDatabase::class.java).build()
        try {
            val identity = DeviceIdentity(context)
            val workspace = identity.workspaceId
            val dao = database.dao()
            dao.putCategoryTree(CategoryTreeRow("tree", workspace, "Activity", archived = true, trashedAtEpochMs = 8_000, updatedAtEpochMs = 8_000))
            dao.putCategories(listOf(CategoryRow("work", workspace, "tree", name = "Work", archived = true, updatedAtEpochMs = 8_000)))
            dao.putPlan(PlanRow("plan", workspace, "Day", 1_000, 5_000, "UTC", updatedAtEpochMs = 1))
            dao.putPlannedEvent(PlannedEventRow("event", workspace, "plan", "tree", "work", null, 2_000, updatedAtEpochMs = 1))
            val repository = T4LRepository(database, identity, SyncStateStore())

            repository.updatePlannedEvent("event", "tree", "work", null, 3_000, now = 10_000)
            assertEquals(3_000L, dao.plannedEvent("event")?.occurredAtEpochMs)
            assertTrue(runCatching { repository.addPlannedEvent("plan", "tree", "work", 4_000, now = 10_000) }.isFailure)
            repository.restoreCategoryTree("tree", 11_000)
            assertEquals(true, dao.category("work")?.archived)
            repository.restoreArchivedCategories("tree", 12_000)
            assertEquals(false, dao.category("work")?.archived)
        } finally { database.close() }
    }

    @Test
    fun factualEditChangesTargetAtomicallyAndCanRemoveTask() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, T4LDatabase::class.java).build()
        try {
            val identity = DeviceIdentity(context)
            val workspace = identity.workspaceId
            val dao = database.dao()
            dao.putCategoryTree(CategoryTreeRow("activity", workspace, "Activity", updatedAtEpochMs = 1))
            dao.putCategoryTree(CategoryTreeRow("location", workspace, "Location", updatedAtEpochMs = 1))
            dao.putCategories(listOf(
                CategoryRow("work", workspace, "activity", name = "Work", updatedAtEpochMs = 1),
                CategoryRow("home", workspace, "location", name = "Home", updatedAtEpochMs = 1),
            ))
            dao.putTask(TaskRow("home-task", workspace, "Go home", "home", estimateMinutes = 30, zoneId = "UTC", status = "completed", updatedAtEpochMs = 1))
            dao.putEvent(EventRow("event", workspace, "activity", "work", null, 1_000, "UTC", updatedAtEpochMs = 1))
            val repository = T4LRepository(database, identity, SyncStateStore())

            repository.updateEvent("event", "location", "home", "home-task", 1_000, now = 10_000)
            val updated = requireNotNull(dao.event("event"))
            assertEquals("location", updated.categoryTreeId)
            assertEquals("home", updated.categoryId)
            assertEquals("home-task", updated.taskId)
            assertEquals(1_000L, updated.occurredAtEpochMs)
            val mutation = dao.pendingMutations(workspace).single()
            val payload = Json.parseToJsonElement(mutation.payloadJson).jsonObject
            assertEquals("\"location\"", payload["categoryTreeId"].toString())
            assertEquals("\"home-task\"", payload["taskId"].toString())

            val invalid = runCatching { repository.updateEvent("event", "activity", "work", "home-task", 1_000, now = 11_000) }
            assertTrue(invalid.exceptionOrNull() is IllegalArgumentException)
            assertEquals(updated, dao.event("event"))
            assertEquals(1, dao.pendingMutations(workspace).size)

            repository.updateEvent("event", "location", "home", null, 1_000, now = 12_000)
            assertNull(dao.event("event")?.taskId)
            assertEquals("null", Json.parseToJsonElement(dao.pendingMutations(workspace).single().payloadJson).jsonObject["taskId"].toString())
        } finally { database.close() }
    }

    @Test
    fun plannedEditKeepsPeriodAndRejectsInvalidTarget() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, T4LDatabase::class.java).build()
        try {
            val identity = DeviceIdentity(context)
            val workspace = identity.workspaceId
            val dao = database.dao()
            dao.putCategoryTree(CategoryTreeRow("tree", workspace, "Activity", updatedAtEpochMs = 1))
            dao.putCategories(listOf(
                CategoryRow("work", workspace, "tree", name = "Work", updatedAtEpochMs = 1),
                CategoryRow("rest", workspace, "tree", name = "Rest", updatedAtEpochMs = 1),
            ))
            dao.putTask(TaskRow("task", workspace, "Rest task", "rest", estimateMinutes = 15, zoneId = "UTC", updatedAtEpochMs = 1))
            dao.putPlan(PlanRow("plan", workspace, "Day", 1_000, 5_000, "UTC", updatedAtEpochMs = 1))
            dao.putPlannedEvent(PlannedEventRow("event", workspace, "plan", "tree", "work", null, 2_000, updatedAtEpochMs = 1))
            val repository = T4LRepository(database, identity, SyncStateStore())

            repository.updatePlannedEvent("event", "tree", "rest", "task", 2_000, now = 10_000)
            val updated = requireNotNull(dao.plannedEvent("event"))
            assertEquals("rest", updated.categoryId)
            assertEquals("task", updated.taskId)
            assertEquals(2_000L, updated.occurredAtEpochMs)
            assertEquals("\"task\"", Json.parseToJsonElement(dao.pendingMutations(workspace).single().payloadJson).jsonObject["taskId"].toString())

            val outside = runCatching { repository.updatePlannedEvent("event", "tree", "rest", null, 5_000, now = 11_000) }
            assertTrue(outside.exceptionOrNull() is IllegalArgumentException)
            assertEquals(updated, dao.plannedEvent("event"))

            repository.updatePlannedEvent("event", "tree", "rest", null, 2_000, now = 12_000)
            assertNull(dao.plannedEvent("event")?.taskId)
            assertEquals(1, dao.pendingMutations(workspace).size)
        } finally { database.close() }
    }
}
