package app.t4l.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskTimeTransitionTest {
    @Test
    fun pausingActiveTaskAppendsTasklessEventOnlyInItsTree() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, T4LDatabase::class.java).build()
        try {
            val identity = DeviceIdentity(context)
            val workspace = identity.workspaceId
            val dao = database.dao()
            dao.putCategoryTree(CategoryTreeRow("tree", workspace, "Activity", updatedAtEpochMs = 1))
            dao.putCategories(listOf(CategoryRow("category", workspace, "tree", name = "Work", updatedAtEpochMs = 1)))
            dao.putTask(TaskRow("task", workspace, "Task", "category", estimateMinutes = 60, zoneId = "UTC", revision = 1, updatedAtEpochMs = 1))
            dao.putEvent(EventRow("start", workspace, "tree", "category", "task", 1_000, "UTC", updatedAtEpochMs = 1_000))
            val repository = T4LRepository(database, identity, SyncStateStore())

            repository.setTaskStatus("task", "paused", 10_000)

            val events = dao.observeEvents(workspace).first().sortedBy { it.occurredAtEpochMs }
            assertEquals(2, events.size)
            assertEquals("task", events[0].taskId)
            assertNull(events[1].taskId)
            assertEquals("category", events[1].categoryId)
            assertEquals(10_000L, events[1].occurredAtEpochMs)
            val pending = dao.pendingMutations(workspace)
            assertEquals(2, pending.size)
            assertNotNull(pending[0].atomicGroupId)
            assertEquals(pending[0].atomicGroupId, pending[1].atomicGroupId)
            dao.putConflict(ConflictRow("conflict", workspace, "task", "task", "{}", "{}", 1, 10_000,
                errorCode = "atomic_group_stale_revision"))
            assertTrue(dao.hasUnresolvedAtomicConflict(workspace))
        } finally {
            database.close()
        }
    }
}
