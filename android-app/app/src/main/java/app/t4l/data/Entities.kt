package app.t4l.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class LocalSyncState { SYNCED, PENDING, CONFLICT }

@Entity(tableName = "category_trees", indices = [Index("workspaceId"), Index(value = ["workspaceId", "sortOrder"])])
data class CategoryTreeRow(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val name: String,
    val role: String = "standard",
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val revision: Long = 0,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val syncState: LocalSyncState = LocalSyncState.PENDING,
)

@Entity(tableName = "categories", indices = [Index("workspaceId"), Index("categoryTreeId"), Index("parentId")])
data class CategoryRow(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val categoryTreeId: String,
    val parentId: String? = null,
    val name: String,
    val loadType: String = "light",
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val revision: Long = 0,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val syncState: LocalSyncState = LocalSyncState.PENDING,
)

@Entity(tableName = "events", indices = [Index("workspaceId"), Index(value = ["categoryTreeId", "occurredAtEpochMs"])])
data class EventRow(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val categoryTreeId: String,
    val categoryId: String?,
    val taskId: String? = null,
    val occurredAtEpochMs: Long,
    val zoneId: String,
    val source: String = "manual",
    val note: String? = null,
    val revision: Long = 0,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val syncState: LocalSyncState = LocalSyncState.PENDING,
)

@Entity(tableName = "tasks", indices = [Index("workspaceId"), Index("categoryId"), Index("parentTaskId")])
data class TaskRow(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val title: String,
    val categoryId: String?,
    val parentTaskId: String? = null,
    val estimateMinutes: Int,
    val remainingEstimateMinutes: Int,
    val deadlineEpochMs: Long? = null,
    val nextActionDateEpochDay: Long? = null,
    val nextActionMinuteOfDay: Int? = null,
    val zoneId: String,
    val value: Int = 50,
    val energy: String = "medium",
    val progress: Int = 0,
    val status: String = "active",
    val splittable: Boolean = true,
    val revision: Long = 0,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val syncState: LocalSyncState = LocalSyncState.PENDING,
)

@Entity(tableName = "task_comments", indices = [Index("workspaceId"), Index("taskId")])
data class TaskCommentRow(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val taskId: String,
    val authorId: String,
    val text: String,
    val revision: Long = 0,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val syncState: LocalSyncState = LocalSyncState.PENDING,
)

@Entity(tableName = "plans", indices = [Index("workspaceId"), Index(value = ["startsAtEpochMs", "endsAtEpochMs"])])
data class PlanRow(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val name: String,
    val kind: String,
    val startsAtEpochMs: Long,
    val endsAtEpochMs: Long,
    val zoneId: String,
    val archived: Boolean = false,
    val revision: Long = 0,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val syncState: LocalSyncState = LocalSyncState.PENDING,
)

@Entity(tableName = "budget_allocations", indices = [Index("workspaceId"), Index(value = ["planId", "categoryId"], unique = true)])
data class BudgetAllocationRow(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val planId: String,
    val categoryId: String,
    val ownMinutes: Int,
    val revision: Long = 0,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val syncState: LocalSyncState = LocalSyncState.PENDING,
)

@Entity(tableName = "planned_events", indices = [Index("workspaceId"), Index("planId"), Index(value = ["planId", "categoryTreeId", "occurredAtEpochMs"])])
data class PlannedEventRow(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val planId: String,
    val categoryTreeId: String,
    val categoryId: String?,
    val taskId: String? = null,
    val occurredAtEpochMs: Long,
    val note: String? = null,
    val revision: Long = 0,
    val updatedAtEpochMs: Long,
    val deletedAtEpochMs: Long? = null,
    val syncState: LocalSyncState = LocalSyncState.PENDING,
)

@Entity(tableName = "outbox", indices = [Index("workspaceId"), Index("createdAtEpochMs")])
data class OutboxRow(
    @PrimaryKey val clientMutationId: String,
    val clientId: String,
    val workspaceId: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val baseRevision: Long,
    val payloadJson: String,
    val createdAtEpochMs: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "sync_cursors")
data class SyncCursorRow(@PrimaryKey val workspaceId: String, val cursor: Long)

@Entity(tableName = "conflicts", indices = [Index("workspaceId"), Index("entityId")])
data class ConflictRow(
    @PrimaryKey val clientMutationId: String,
    val workspaceId: String,
    val entityType: String,
    val entityId: String,
    val localPayloadJson: String,
    val serverPayloadJson: String,
    val serverRevision: Long,
    val createdAtEpochMs: Long,
    val operation: String = "upsert",
    val errorCode: String? = null,
)

@Entity(tableName = "user_profiles")
data class UserProfileRow(
    @PrimaryKey val userId: String,
    val birthDateEpochDay: Long? = null,
    val lifeExpectancyYears: Double? = null,
    val revision: Long = 0,
    val avatarRevision: Long = 0,
    val hasAvatar: Boolean = false,
    val localAvatarPath: String? = null,
    val updatedAtEpochMs: Long = 0,
)

@Entity(tableName = "profile_mutations", indices = [Index("userId")])
data class ProfileMutationRow(
    @PrimaryKey val clientMutationId: String,
    val userId: String,
    val baseRevision: Long,
    val birthDateEpochDay: Long?,
    val lifeExpectancyYears: Double?,
    val changedFields: String,
    val createdAtEpochMs: Long,
    val baseSnapshotJson: String? = null,
)

@Entity(tableName = "avatar_mutations", indices = [Index("userId")])
data class AvatarMutationRow(
    @PrimaryKey val clientMutationId: String,
    val userId: String,
    val baseRevision: Long,
    val operation: String,
    val localPath: String?,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "personal_conflicts", indices = [Index("userId")])
data class PersonalConflictRow(
    @PrimaryKey val clientMutationId: String,
    val userId: String,
    val kind: String,
    val baseRevision: Long,
    val serverRevision: Long,
    val localPayloadJson: String,
    val serverPayloadJson: String,
    val localFilePath: String? = null,
    val serverFilePath: String? = null,
    val createdAtEpochMs: Long,
)
