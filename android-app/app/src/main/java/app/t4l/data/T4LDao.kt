package app.t4l.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface T4LDao {
    @Query("SELECT * FROM user_profiles WHERE userId=:userId")
    fun observeProfile(userId: String): Flow<UserProfileRow?>
    @Query("SELECT * FROM personal_conflicts WHERE userId=:userId ORDER BY createdAtEpochMs")
    fun observePersonalConflicts(userId: String): Flow<List<PersonalConflictRow>>
    @Query("SELECT * FROM category_trees WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL AND archived=0 ORDER BY sortOrder,name")
    fun observeCategoryTrees(workspaceId: String): Flow<List<CategoryTreeRow>>
    @Query("SELECT * FROM category_trees WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL AND archived=1 AND purgedAtEpochMs IS NULL ORDER BY trashedAtEpochMs DESC,name")
    fun observeTrashedCategoryTrees(workspaceId: String): Flow<List<CategoryTreeRow>>
    @Query("SELECT * FROM category_trees WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL ORDER BY sortOrder,name")
    fun observeHistoricalCategoryTrees(workspaceId: String): Flow<List<CategoryTreeRow>>
    @Query("SELECT * FROM categories WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL ORDER BY categoryTreeId,sortOrder,name")
    fun observeCategories(workspaceId: String): Flow<List<CategoryRow>>
    @Query("SELECT * FROM events WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL ORDER BY occurredAtEpochMs DESC")
    fun observeEvents(workspaceId: String): Flow<List<EventRow>>
    @Query("SELECT * FROM tasks WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL ORDER BY sortOrder,title,id")
    fun observeTasks(workspaceId: String): Flow<List<TaskRow>>
    @Query("SELECT * FROM task_comments WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL ORDER BY updatedAtEpochMs DESC")
    fun observeTaskComments(workspaceId: String): Flow<List<TaskCommentRow>>
    @Query("SELECT * FROM plans WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL AND archived=0 ORDER BY startsAtEpochMs DESC")
    fun observePlans(workspaceId: String): Flow<List<PlanRow>>
    @Query("SELECT * FROM plans WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL AND archived=1 ORDER BY updatedAtEpochMs DESC")
    fun observeArchivedPlans(workspaceId: String): Flow<List<PlanRow>>
    @Query("SELECT * FROM budget_allocations WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL")
    fun observeBudgetAllocations(workspaceId: String): Flow<List<BudgetAllocationRow>>
    @Query("SELECT * FROM planned_events WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL ORDER BY occurredAtEpochMs")
    fun observePlannedEvents(workspaceId: String): Flow<List<PlannedEventRow>>
    @Query("SELECT * FROM outbox WHERE workspaceId=:workspaceId ORDER BY createdAtEpochMs")
    fun observeOutbox(workspaceId: String): Flow<List<OutboxRow>>
    @Query("SELECT * FROM conflicts WHERE workspaceId=:workspaceId ORDER BY createdAtEpochMs")
    fun observeConflicts(workspaceId: String): Flow<List<ConflictRow>>
    @Query("SELECT COUNT(*) FROM category_trees WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL")
    suspend fun categoryTreeCount(workspaceId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCategoryTree(row: CategoryTreeRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCategories(rows: List<CategoryRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putEvent(row: EventRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTask(row: TaskRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTaskComment(row: TaskCommentRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPlan(row: PlanRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putBudgetAllocation(row: BudgetAllocationRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPlannedEvent(row: PlannedEventRow)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun enqueue(row: OutboxRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putOutbox(row: OutboxRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConflict(row: ConflictRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCursor(row: SyncCursorRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProfile(row: UserProfileRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProfileMutation(row: ProfileMutationRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putAvatarMutation(row: AvatarMutationRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPersonalConflict(row: PersonalConflictRow)

    @Query("SELECT * FROM outbox WHERE workspaceId=:workspaceId ORDER BY createdAtEpochMs, clientMutationId LIMIT :limit") suspend fun pendingMutations(workspaceId: String, limit: Int = 100): List<OutboxRow>
    @Query("SELECT * FROM outbox WHERE atomicGroupId=:groupId ORDER BY createdAtEpochMs, clientMutationId") suspend fun pendingAtomicGroup(groupId: String): List<OutboxRow>
    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE entityType=:entityType AND entityId=:entityId AND atomicGroupId IS NOT NULL)") suspend fun hasPendingAtomicForEntity(entityType: String, entityId: String): Boolean
    @Query("UPDATE outbox SET baseRevision=:revision WHERE clientMutationId=:id") suspend fun updateMutationRevision(id: String, revision: Long)
    @Query("SELECT * FROM outbox WHERE clientMutationId=:id") suspend fun pendingMutation(id: String): OutboxRow?
    @Query("DELETE FROM outbox WHERE clientMutationId IN (:ids)") suspend fun deleteMutations(ids: List<String>)
    @Query("UPDATE outbox SET attemptCount=attemptCount+1,lastError=:error WHERE clientMutationId IN (:ids)") suspend fun markMutationFailure(ids: List<String>, error: String)
    @Query("DELETE FROM outbox WHERE entityType=:entityType AND entityId=:entityId") suspend fun deletePendingForEntity(entityType: String, entityId: String)
    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE entityType=:entityType AND entityId=:entityId)") suspend fun hasPendingForEntity(entityType: String, entityId: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM conflicts WHERE entityType=:entityType AND entityId=:entityId)") suspend fun hasConflictForEntity(entityType: String, entityId: String): Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM conflicts WHERE workspaceId=:workspaceId AND (linkedEventId IS NOT NULL OR errorCode LIKE 'atomic_group_%'))") suspend fun hasUnresolvedAtomicConflict(workspaceId: String): Boolean
    @Query("SELECT * FROM conflicts WHERE clientMutationId=:id") suspend fun conflict(id: String): ConflictRow?
    @Query("DELETE FROM conflicts WHERE clientMutationId=:id") suspend fun deleteConflict(id: String)
    @Query("UPDATE outbox SET attemptCount=0,lastError=NULL WHERE clientMutationId=:id") suspend fun retryMutation(id: String)
    @Query("SELECT * FROM sync_cursors WHERE workspaceId=:workspaceId") suspend fun cursor(workspaceId: String): SyncCursorRow?
    @Query("SELECT * FROM user_profiles WHERE userId=:userId") suspend fun profile(userId: String): UserProfileRow?
    @Query("SELECT * FROM profile_mutations WHERE userId=:userId ORDER BY createdAtEpochMs") suspend fun profileMutations(userId: String): List<ProfileMutationRow>
    @Query("SELECT * FROM avatar_mutations WHERE userId=:userId ORDER BY createdAtEpochMs") suspend fun avatarMutations(userId: String): List<AvatarMutationRow>
    @Query("DELETE FROM profile_mutations WHERE userId=:userId") suspend fun deleteProfileMutations(userId: String)
    @Query("DELETE FROM avatar_mutations WHERE userId=:userId") suspend fun deleteAvatarMutations(userId: String)
    @Query("DELETE FROM profile_mutations WHERE clientMutationId=:id") suspend fun deleteProfileMutation(id: String)
    @Query("DELETE FROM avatar_mutations WHERE clientMutationId=:id") suspend fun deleteAvatarMutation(id: String)
    @Query("SELECT * FROM personal_conflicts WHERE clientMutationId=:id") suspend fun personalConflict(id: String): PersonalConflictRow?
    @Query("SELECT * FROM personal_conflicts WHERE userId=:userId ORDER BY createdAtEpochMs") suspend fun personalConflicts(userId: String): List<PersonalConflictRow>
    @Query("DELETE FROM personal_conflicts WHERE clientMutationId=:id") suspend fun deletePersonalConflict(id: String)
    @Query("DELETE FROM personal_conflicts WHERE userId=:userId") suspend fun deletePersonalConflicts(userId: String)
    @Query("DELETE FROM user_profiles WHERE userId=:userId") suspend fun deleteProfile(userId: String)

    @Query("SELECT * FROM category_trees WHERE id=:id") suspend fun categoryTree(id: String): CategoryTreeRow?
    @Query("SELECT * FROM categories WHERE id=:id") suspend fun category(id: String): CategoryRow?
    @Query("SELECT * FROM categories WHERE parentId=:parentId AND deletedAtEpochMs IS NULL") suspend fun children(parentId: String): List<CategoryRow>
    @Query("SELECT * FROM categories WHERE categoryTreeId=:treeId AND deletedAtEpochMs IS NULL") suspend fun categoriesInTree(treeId: String): List<CategoryRow>
    @Query("SELECT * FROM events WHERE id=:id") suspend fun event(id: String): EventRow?
    @Query("SELECT * FROM events WHERE categoryTreeId=:treeId AND deletedAtEpochMs IS NULL ORDER BY occurredAtEpochMs DESC,id DESC LIMIT 1") suspend fun latestEventInTree(treeId: String): EventRow?
    @Query("SELECT * FROM tasks WHERE id=:id") suspend fun task(id: String): TaskRow?
    @Query("SELECT * FROM tasks WHERE parentTaskId=:parentId AND deletedAtEpochMs IS NULL") suspend fun taskChildren(parentId: String): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE categoryId=:categoryId AND deletedAtEpochMs IS NULL") suspend fun tasksForCategory(categoryId: String): List<TaskRow>
    @Query("SELECT * FROM tasks WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL ORDER BY sortOrder,title,id") suspend fun tasks(workspaceId: String): List<TaskRow>
    @Query("SELECT * FROM task_comments WHERE id=:id") suspend fun taskComment(id: String): TaskCommentRow?
    @Query("SELECT * FROM plans WHERE id=:id") suspend fun plan(id: String): PlanRow?
    @Query("SELECT * FROM plans WHERE workspaceId=:workspaceId AND deletedAtEpochMs IS NULL AND (endsAtEpochMs<=startsAtEpochMs OR startsAtEpochMs=0) ") suspend fun invalidPlans(workspaceId: String): List<PlanRow>
    @Query("SELECT * FROM budget_allocations WHERE planId=:planId AND deletedAtEpochMs IS NULL") suspend fun budgetsForPlan(planId: String): List<BudgetAllocationRow>
    @Query("SELECT * FROM planned_events WHERE planId=:planId AND deletedAtEpochMs IS NULL") suspend fun plannedEventsForPlan(planId: String): List<PlannedEventRow>
    @Query("SELECT * FROM budget_allocations WHERE planId=:planId AND categoryId=:categoryId LIMIT 1") suspend fun budgetAllocation(planId: String, categoryId: String): BudgetAllocationRow?
    @Query("SELECT * FROM planned_events WHERE id=:id") suspend fun plannedEvent(id: String): PlannedEventRow?
    @Query("DELETE FROM category_trees WHERE id=:id") suspend fun hardDeleteCategoryTree(id: String)
    @Query("DELETE FROM categories WHERE id=:id") suspend fun hardDeleteCategory(id: String)
    @Query("DELETE FROM events WHERE id=:id") suspend fun hardDeleteEvent(id: String)
    @Query("DELETE FROM tasks WHERE id=:id") suspend fun hardDeleteTask(id: String)
    @Query("DELETE FROM task_comments WHERE id=:id") suspend fun hardDeleteTaskComment(id: String)
    @Query("DELETE FROM plans WHERE id=:id") suspend fun hardDeletePlan(id: String)
    @Query("DELETE FROM budget_allocations WHERE id=:id") suspend fun hardDeleteBudgetAllocation(id: String)
    @Query("DELETE FROM planned_events WHERE id=:id") suspend fun hardDeletePlannedEvent(id: String)
}
