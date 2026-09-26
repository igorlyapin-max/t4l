package app.t4l.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

class DatabaseConverters {
    @TypeConverter fun fromSyncState(value: LocalSyncState): String = value.name
    @TypeConverter fun toSyncState(value: String): LocalSyncState = LocalSyncState.valueOf(value)
}

@Database(
    entities = [
        CategoryTreeRow::class, CategoryRow::class, EventRow::class, TaskRow::class,
        TaskCommentRow::class, PlanRow::class, BudgetAllocationRow::class, PlannedEventRow::class,
        OutboxRow::class, SyncCursorRow::class, ConflictRow::class,
        UserProfileRow::class, ProfileMutationRow::class, AvatarMutationRow::class, PersonalConflictRow::class,
    ],
    version = 9,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class T4LDatabase : RoomDatabase() {
    abstract fun dao(): T4LDao

    companion object {
        fun create(context: Context): T4LDatabase = Room.databaseBuilder(
            context.applicationContext,
            T4LDatabase::class.java,
            "t4l.db",
        ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).fallbackToDestructiveMigrationFrom(true, 1).build()

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox ADD COLUMN atomicGroupId TEXT")
                db.execSQL("ALTER TABLE conflicts ADD COLUMN linkedEventId TEXT")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE category_trees ADD COLUMN trashedAtEpochMs INTEGER")
                db.execSQL("ALTER TABLE category_trees ADD COLUMN purgedAtEpochMs INTEGER")
                db.execSQL("UPDATE category_trees SET trashedAtEpochMs=? WHERE archived=1 AND deletedAtEpochMs IS NULL", arrayOf(System.currentTimeMillis()))
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE tasks_new (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, title TEXT NOT NULL, categoryId TEXT, parentTaskId TEXT, estimateMinutes INTEGER NOT NULL, deadlineEpochMs INTEGER, nextActionDateEpochDay INTEGER, nextActionMinuteOfDay INTEGER, zoneId TEXT NOT NULL, value INTEGER NOT NULL, energy TEXT NOT NULL, progress INTEGER NOT NULL, status TEXT NOT NULL, splittable INTEGER NOT NULL, sortOrder INTEGER NOT NULL, revision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, deletedAtEpochMs INTEGER, syncState TEXT NOT NULL)")
                db.execSQL("INSERT INTO tasks_new SELECT id,workspaceId,title,categoryId,parentTaskId,estimateMinutes,deadlineEpochMs,nextActionDateEpochDay,nextActionMinuteOfDay,zoneId,value,energy,progress,status,splittable,sortOrder,revision,updatedAtEpochMs,deletedAtEpochMs,syncState FROM tasks")
                db.execSQL("DROP TABLE tasks")
                db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")
                db.execSQL("CREATE INDEX index_tasks_workspaceId ON tasks(workspaceId)")
                db.execSQL("CREATE INDEX index_tasks_categoryId ON tasks(categoryId)")
                db.execSQL("CREATE INDEX index_tasks_parentTaskId ON tasks(parentTaskId)")
                db.execSQL("CREATE INDEX index_tasks_workspaceId_sortOrder ON tasks(workspaceId,sortOrder)")
                db.execSQL("CREATE TABLE plans_new (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, name TEXT NOT NULL, startsAtEpochMs INTEGER NOT NULL, endsAtEpochMs INTEGER NOT NULL, zoneId TEXT NOT NULL, archived INTEGER NOT NULL, revision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, deletedAtEpochMs INTEGER, syncState TEXT NOT NULL)")
                db.execSQL("INSERT INTO plans_new SELECT id,workspaceId,name,startsAtEpochMs,endsAtEpochMs,zoneId,archived,revision,updatedAtEpochMs,deletedAtEpochMs,syncState FROM plans")
                db.execSQL("DROP TABLE plans")
                db.execSQL("ALTER TABLE plans_new RENAME TO plans")
                db.execSQL("CREATE INDEX index_plans_workspaceId ON plans(workspaceId)")
                db.execSQL("CREATE INDEX index_plans_startsAtEpochMs_endsAtEpochMs ON plans(startsAtEpochMs,endsAtEpochMs)")
                for ((table, key) in listOf("outbox" to "clientMutationId", "conflicts" to "clientMutationId")) {
                    val payloadColumns = if (table == "outbox") listOf("payloadJson") else listOf("localPayloadJson", "serverPayloadJson")
                    for (column in payloadColumns) {
                        db.query("SELECT $key,$column,entityType FROM $table WHERE lower(entityType) IN ('task','plan')").use { cursor ->
                            while (cursor.moveToNext()) {
                                val payload = runCatching { org.json.JSONObject(cursor.getString(1)) }.getOrNull() ?: continue
                                if (cursor.getString(2).equals("task", true)) payload.remove("remainingEstimateMinutes") else payload.remove("kind")
                                db.execSQL("UPDATE $table SET $column=? WHERE $key=?", arrayOf(payload.toString(), cursor.getString(0)))
                            }
                        }
                    }
                }
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tasks SET sortOrder = (SELECT COUNT(*) * 10 FROM tasks AS ranked WHERE ranked.workspaceId = tasks.workspaceId AND (COALESCE(ranked.nextActionDateEpochDay, 9223372036854775807) < COALESCE(tasks.nextActionDateEpochDay, 9223372036854775807) OR (COALESCE(ranked.nextActionDateEpochDay, 9223372036854775807) = COALESCE(tasks.nextActionDateEpochDay, 9223372036854775807) AND (LOWER(ranked.title) < LOWER(tasks.title) OR (LOWER(ranked.title) = LOWER(tasks.title) AND ranked.id < tasks.id)))))")
                db.execSQL("UPDATE outbox SET payloadJson = CASE WHEN TRIM(payloadJson) = '{}' THEN '{\"sortOrder\":' || COALESCE((SELECT sortOrder FROM tasks WHERE tasks.id = outbox.entityId), 0) || '}' ELSE SUBSTR(TRIM(payloadJson), 1, LENGTH(TRIM(payloadJson)) - 1) || ',\"sortOrder\":' || COALESCE((SELECT sortOrder FROM tasks WHERE tasks.id = outbox.entityId), 0) || '}' END WHERE lower(entityType) = 'task' AND operation = 'upsert'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_workspaceId_sortOrder ON tasks(workspaceId,sortOrder)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profile_mutations ADD COLUMN baseSnapshotJson TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS personal_conflicts (clientMutationId TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, kind TEXT NOT NULL, baseRevision INTEGER NOT NULL, serverRevision INTEGER NOT NULL, localPayloadJson TEXT NOT NULL, serverPayloadJson TEXT NOT NULL, localFilePath TEXT, serverFilePath TEXT, createdAtEpochMs INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_personal_conflicts_userId ON personal_conflicts(userId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS user_profiles (userId TEXT NOT NULL PRIMARY KEY, birthDateEpochDay INTEGER, lifeExpectancyYears REAL, revision INTEGER NOT NULL, avatarRevision INTEGER NOT NULL, hasAvatar INTEGER NOT NULL, localAvatarPath TEXT, updatedAtEpochMs INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS profile_mutations (clientMutationId TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, baseRevision INTEGER NOT NULL, birthDateEpochDay INTEGER, lifeExpectancyYears REAL, changedFields TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_profile_mutations_userId ON profile_mutations(userId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS avatar_mutations (clientMutationId TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, baseRevision INTEGER NOT NULL, operation TEXT NOT NULL, localPath TEXT, createdAtEpochMs INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_avatar_mutations_userId ON avatar_mutations(userId)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conflicts ADD COLUMN operation TEXT NOT NULL DEFAULT 'upsert'")
                db.execSQL("ALTER TABLE conflicts ADD COLUMN errorCode TEXT")
            }
        }

        @Deprecated("Schema v1 is intentionally unsupported; Room performs a destructive reset.")
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE category_trees (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL, sortOrder INTEGER NOT NULL, archived INTEGER NOT NULL, revision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, deletedAtEpochMs INTEGER, syncState TEXT NOT NULL)")
                db.execSQL("INSERT INTO category_trees SELECT * FROM timelines")
                db.execSQL("CREATE INDEX index_category_trees_workspaceId ON category_trees(workspaceId)")
                db.execSQL("CREATE INDEX index_category_trees_workspaceId_sortOrder ON category_trees(workspaceId,sortOrder)")

                db.execSQL("ALTER TABLE categories RENAME TO categories_v1")
                db.execSQL("DROP INDEX index_categories_workspaceId")
                db.execSQL("DROP INDEX index_categories_timelineId")
                db.execSQL("DROP INDEX index_categories_parentId")
                db.execSQL("CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, categoryTreeId TEXT NOT NULL, parentId TEXT, name TEXT NOT NULL, loadType TEXT NOT NULL, sortOrder INTEGER NOT NULL, archived INTEGER NOT NULL, revision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, deletedAtEpochMs INTEGER, syncState TEXT NOT NULL)")
                db.execSQL("INSERT INTO categories SELECT id,workspaceId,timelineId,parentId,name,loadType,sortOrder,archived,revision,updatedAtEpochMs,deletedAtEpochMs,syncState FROM categories_v1")
                db.execSQL("CREATE INDEX index_categories_workspaceId ON categories(workspaceId)")
                db.execSQL("CREATE INDEX index_categories_categoryTreeId ON categories(categoryTreeId)")
                db.execSQL("CREATE INDEX index_categories_parentId ON categories(parentId)")

                db.execSQL("ALTER TABLE events RENAME TO events_v1")
                db.execSQL("DROP INDEX index_events_workspaceId")
                db.execSQL("DROP INDEX index_events_timelineId_occurredAtEpochMs")
                db.execSQL("CREATE TABLE events (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, categoryTreeId TEXT NOT NULL, categoryId TEXT, taskId TEXT, occurredAtEpochMs INTEGER NOT NULL, zoneId TEXT NOT NULL, source TEXT NOT NULL, note TEXT, revision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, deletedAtEpochMs INTEGER, syncState TEXT NOT NULL)")
                db.execSQL("INSERT INTO events SELECT id,workspaceId,timelineId,categoryId,NULL,occurredAtEpochMs,zoneId,source,note,revision,updatedAtEpochMs,deletedAtEpochMs,syncState FROM events_v1")
                db.execSQL("CREATE INDEX index_events_workspaceId ON events(workspaceId)")
                db.execSQL("CREATE INDEX index_events_categoryTreeId_occurredAtEpochMs ON events(categoryTreeId,occurredAtEpochMs)")

                db.execSQL("ALTER TABLE tasks RENAME TO tasks_v1")
                db.execSQL("DROP INDEX index_tasks_workspaceId")
                db.execSQL("DROP INDEX index_tasks_categoryId")
                db.execSQL("CREATE TABLE tasks (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, title TEXT NOT NULL, categoryId TEXT, parentTaskId TEXT, estimateMinutes INTEGER NOT NULL, remainingEstimateMinutes INTEGER NOT NULL, deadlineEpochMs INTEGER, nextActionDateEpochDay INTEGER, nextActionMinuteOfDay INTEGER, zoneId TEXT NOT NULL, value INTEGER NOT NULL, energy TEXT NOT NULL, progress INTEGER NOT NULL, status TEXT NOT NULL, splittable INTEGER NOT NULL, revision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, deletedAtEpochMs INTEGER, syncState TEXT NOT NULL)")
                db.execSQL("INSERT INTO tasks SELECT t.id,t.workspaceId,COALESCE(c.name,'Task'),t.categoryId,NULL,t.initialEstimateMinutes,t.remainingEstimateMinutes,t.deadlineEpochMs,CAST(julianday('now')-2440587.5 AS INTEGER),NULL,'UTC',t.value,t.energy,t.progress,CASE WHEN t.status='done' THEN 'completed' WHEN t.status='cancelled' THEN 'cancelled' ELSE 'active' END,t.splittable,t.revision,t.updatedAtEpochMs,t.deletedAtEpochMs,t.syncState FROM tasks_v1 t LEFT JOIN categories c ON c.id=t.categoryId")
                db.execSQL("CREATE INDEX index_tasks_workspaceId ON tasks(workspaceId)")
                db.execSQL("CREATE INDEX index_tasks_categoryId ON tasks(categoryId)")
                db.execSQL("CREATE INDEX index_tasks_parentTaskId ON tasks(parentTaskId)")

                db.execSQL("CREATE TABLE task_comments (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, taskId TEXT NOT NULL, authorId TEXT NOT NULL, text TEXT NOT NULL, revision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, deletedAtEpochMs INTEGER, syncState TEXT NOT NULL)")
                db.execSQL("CREATE INDEX index_task_comments_workspaceId ON task_comments(workspaceId)")
                db.execSQL("CREATE INDEX index_task_comments_taskId ON task_comments(taskId)")
                db.execSQL("CREATE TABLE plans (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, startsAtEpochMs INTEGER NOT NULL, endsAtEpochMs INTEGER NOT NULL, zoneId TEXT NOT NULL, archived INTEGER NOT NULL, revision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, deletedAtEpochMs INTEGER, syncState TEXT NOT NULL)")
                db.execSQL("INSERT INTO plans SELECT d.id,d.workspaceId,'Plan ' || d.dateEpochDay,'timeline',COALESCE((SELECT MIN(startsAtEpochMs) FROM availability_windows w WHERE w.dailyPlanId=d.id),d.dateEpochDay*86400000),COALESCE((SELECT MAX(endsAtEpochMs) FROM availability_windows w WHERE w.dailyPlanId=d.id),(d.dateEpochDay+1)*86400000),d.zoneId,0,d.revision,d.updatedAtEpochMs,d.deletedAtEpochMs,d.syncState FROM daily_plans d")
                db.execSQL("CREATE INDEX index_plans_workspaceId ON plans(workspaceId)")
                db.execSQL("CREATE INDEX index_plans_startsAtEpochMs_endsAtEpochMs ON plans(startsAtEpochMs,endsAtEpochMs)")
                db.execSQL("CREATE TABLE budget_allocations (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, planId TEXT NOT NULL, categoryId TEXT NOT NULL, ownMinutes INTEGER NOT NULL, revision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, deletedAtEpochMs INTEGER, syncState TEXT NOT NULL)")
                db.execSQL("CREATE INDEX index_budget_allocations_workspaceId ON budget_allocations(workspaceId)")
                db.execSQL("CREATE UNIQUE INDEX index_budget_allocations_planId_categoryId ON budget_allocations(planId,categoryId)")
                db.execSQL("CREATE TABLE planned_events (id TEXT NOT NULL PRIMARY KEY, workspaceId TEXT NOT NULL, planId TEXT NOT NULL, categoryTreeId TEXT NOT NULL, categoryId TEXT, taskId TEXT, occurredAtEpochMs INTEGER NOT NULL, note TEXT, revision INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, deletedAtEpochMs INTEGER, syncState TEXT NOT NULL)")
                db.execSQL("INSERT INTO planned_events SELECT p.id,p.workspaceId,p.dailyPlanId,COALESCE(c.categoryTreeId,''),p.categoryId,p.taskId,p.startsAtEpochMs,NULL,p.revision,p.updatedAtEpochMs,p.deletedAtEpochMs,p.syncState FROM planned_blocks p LEFT JOIN categories c ON c.id=p.categoryId")
                db.execSQL("CREATE INDEX index_planned_events_workspaceId ON planned_events(workspaceId)")
                db.execSQL("CREATE INDEX index_planned_events_planId ON planned_events(planId)")
                db.execSQL("CREATE INDEX index_planned_events_planId_categoryTreeId_occurredAtEpochMs ON planned_events(planId,categoryTreeId,occurredAtEpochMs)")

                db.execSQL("UPDATE outbox SET entityType='categoryTree', payloadJson=replace(payloadJson,'timelineId','categoryTreeId') WHERE entityType='timeline'")
                db.execSQL("UPDATE outbox SET payloadJson=replace(payloadJson,'timelineId','categoryTreeId') WHERE entityType IN ('category','event')")
                db.execSQL("UPDATE outbox SET payloadJson=replace(replace(replace(payloadJson,'initialEstimateMinutes','estimateMinutes'),'\"status\":\"open\"','\"status\":\"active\"'),'\"status\":\"done\"','\"status\":\"completed\"') WHERE entityType='task'")
                db.execSQL("UPDATE outbox SET entityType='plan',payloadJson=(SELECT '{\"name\":\"Migrated plan\",\"kind\":\"timeline\",\"startsAt\":\"' || datetime(p.startsAtEpochMs/1000,'unixepoch') || 'Z\",\"endsAt\":\"' || datetime(p.endsAtEpochMs/1000,'unixepoch') || 'Z\",\"zoneId\":\"' || p.zoneId || '\",\"archived\":false}' FROM plans p WHERE p.id=outbox.entityId) WHERE entityType='dailyPlan'")
                db.execSQL("UPDATE outbox SET entityType='plannedEvent',payloadJson=(SELECT '{\"planId\":\"' || p.planId || '\",\"categoryTreeId\":\"' || p.categoryTreeId || '\",\"categoryId\":' || CASE WHEN p.categoryId IS NULL THEN 'null' ELSE '\"' || p.categoryId || '\"' END || ',\"taskId\":' || CASE WHEN p.taskId IS NULL THEN 'null' ELSE '\"' || p.taskId || '\"' END || ',\"occurredAt\":\"' || datetime(p.occurredAtEpochMs/1000,'unixepoch') || 'Z\"}' FROM planned_events p WHERE p.id=outbox.entityId) WHERE entityType='plannedBlock'")
                db.execSQL("DELETE FROM outbox WHERE entityType='availabilityWindow'")
                db.execSQL("DROP TABLE timelines")
                db.execSQL("DROP TABLE categories_v1")
                db.execSQL("DROP TABLE events_v1")
                db.execSQL("DROP TABLE tasks_v1")
                db.execSQL("DROP TABLE daily_plans")
                db.execSQL("DROP TABLE availability_windows")
                db.execSQL("DROP TABLE planned_blocks")
            }
        }
    }
}
