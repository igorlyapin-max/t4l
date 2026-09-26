package app.t4l.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class T4LDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        T4LDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate2To3PreservesConflictAndAddsResolutionMetadata() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO conflicts VALUES " +
                    "('mutation-1','workspace-1','task','task-1','{\"title\":\"Local\"}','{\"title\":\"Server\"}',2,1000)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 3, true, T4LDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT operation,errorCode FROM conflicts WHERE clientMutationId='mutation-1'").use {
                check(it.moveToFirst())
                assertEquals("upsert", it.getString(0))
                assertEquals(true, it.isNull(1))
            }
        }
    }

    @Test
    fun migrate3To4AddsOfflineProfileQueues() {
        helper.createDatabase(TEST_DB_V4, 3).close()

        helper.runMigrationsAndValidate(TEST_DB_V4, 4, true, T4LDatabase.MIGRATION_3_4).use { db ->
            db.execSQL("INSERT INTO user_profiles VALUES ('user-1',10000,78.6,2,1,1,NULL,1000)")
            db.query("SELECT birthDateEpochDay,lifeExpectancyYears FROM user_profiles WHERE userId='user-1'").use {
                check(it.moveToFirst())
                assertEquals(10000L, it.getLong(0))
                assertEquals(78.6, it.getDouble(1), 0.001)
            }
        }
    }

    @Test
    fun migrate4To5PreservesPendingProfileAndAddsPersonalConflicts() {
        helper.createDatabase(TEST_DB_V5, 4).apply {
            execSQL("INSERT INTO profile_mutations VALUES ('mutation-1','user-1',3,10000,78.6,'birthDate,lifeExpectancyYears',1000)")
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB_V5, 5, true, T4LDatabase.MIGRATION_4_5).use { db ->
            db.query("SELECT changedFields,baseSnapshotJson FROM profile_mutations WHERE clientMutationId='mutation-1'").use {
                check(it.moveToFirst())
                assertEquals("birthDate,lifeExpectancyYears", it.getString(0))
                assertEquals(true, it.isNull(1))
            }
            db.execSQL("INSERT INTO personal_conflicts VALUES ('mutation-2','user-1','profile',3,4,'{}','{}',NULL,NULL,2000)")
        }
    }

    @Test
    fun migrate5To6AddsDeterministicTaskOrder() {
        helper.createDatabase(TEST_DB_V6, 5).apply {
            execSQL("INSERT INTO tasks (id,workspaceId,title,categoryId,parentTaskId,estimateMinutes,remainingEstimateMinutes,deadlineEpochMs,nextActionDateEpochDay,nextActionMinuteOfDay,zoneId,value,energy,progress,status,splittable,revision,updatedAtEpochMs,deletedAtEpochMs,syncState) VALUES ('b','workspace','Later',NULL,'parent',10,10,NULL,20,NULL,'UTC',50,'medium',0,'active',1,0,1,NULL,'SYNCED')")
            execSQL("INSERT INTO tasks (id,workspaceId,title,categoryId,parentTaskId,estimateMinutes,remainingEstimateMinutes,deadlineEpochMs,nextActionDateEpochDay,nextActionMinuteOfDay,zoneId,value,energy,progress,status,splittable,revision,updatedAtEpochMs,deletedAtEpochMs,syncState) VALUES ('a','workspace','Sooner',NULL,'parent',10,10,NULL,10,NULL,'UTC',50,'medium',0,'active',1,0,1,NULL,'SYNCED')")
            execSQL("INSERT INTO outbox VALUES ('mutation','client','workspace','task','b','upsert',0,'{\"title\":\"Later\"}',1,0,NULL)")
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB_V6, 6, true, T4LDatabase.MIGRATION_5_6).use { db ->
            db.query("SELECT id,sortOrder FROM tasks ORDER BY sortOrder").use {
                check(it.moveToFirst())
                assertEquals("a", it.getString(0))
                assertEquals(0, it.getInt(1))
                check(it.moveToNext())
                assertEquals("b", it.getString(0))
                assertEquals(10, it.getInt(1))
            }
            db.query("SELECT payloadJson FROM outbox WHERE clientMutationId='mutation'").use {
                check(it.moveToFirst())
                assertEquals("{\"title\":\"Later\",\"sortOrder\":10}", it.getString(0))
            }
        }
    }

    @Test
    fun migrate6To7PreservesUnifiedPlansAndRemovesObsoletePayloadFields() {
        helper.createDatabase(TEST_DB_V7, 6).apply {
            execSQL("INSERT INTO tasks (id,workspaceId,title,categoryId,parentTaskId,estimateMinutes,remainingEstimateMinutes,zoneId,value,energy,progress,status,splittable,sortOrder,revision,updatedAtEpochMs,syncState) VALUES ('task','workspace','Task','category',NULL,60,20,'UTC',50,'medium',0,'active',1,10,2,1000,'PENDING')")
            execSQL("INSERT INTO plans (id,workspaceId,name,kind,startsAtEpochMs,endsAtEpochMs,zoneId,archived,revision,updatedAtEpochMs,syncState) VALUES ('plan','workspace','Mixed','budget',1000,2000,'UTC',0,3,1000,'PENDING')")
            execSQL("INSERT INTO budget_allocations (id,workspaceId,planId,categoryId,ownMinutes,revision,updatedAtEpochMs,syncState) VALUES ('budget','workspace','plan','category',12,1,1000,'PENDING')")
            execSQL("INSERT INTO planned_events (id,workspaceId,planId,categoryTreeId,categoryId,occurredAtEpochMs,revision,updatedAtEpochMs,syncState) VALUES ('event','workspace','plan','tree','category',1200,1,1000,'PENDING')")
            execSQL("INSERT INTO outbox VALUES ('mutation','client','workspace','task','task','upsert',2,'{\"estimateMinutes\":60,\"remainingEstimateMinutes\":20}',1000,0,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB_V7, 7, true, T4LDatabase.MIGRATION_6_7).use { db ->
            db.query("SELECT estimateMinutes FROM tasks WHERE id='task'").use { check(it.moveToFirst()); assertEquals(60, it.getInt(0)) }
            db.query("SELECT name FROM plans WHERE id='plan'").use { check(it.moveToFirst()); assertEquals("Mixed", it.getString(0)) }
            db.query("SELECT ownMinutes FROM budget_allocations WHERE id='budget'").use { check(it.moveToFirst()); assertEquals(12, it.getInt(0)) }
            db.query("SELECT occurredAtEpochMs FROM planned_events WHERE id='event'").use { check(it.moveToFirst()); assertEquals(1200L, it.getLong(0)) }
            db.query("SELECT payloadJson FROM outbox WHERE clientMutationId='mutation'").use { check(it.moveToFirst()); assertEquals("{\"estimateMinutes\":60}", it.getString(0)) }
        }
    }

    @Test
    fun migrate7To8GivesPreviouslyArchivedTreesFreshRetentionWindow() {
        helper.createDatabase(TEST_DB_V8, 7).apply {
            execSQL("INSERT INTO category_trees (id,workspaceId,name,role,sortOrder,archived,revision,updatedAtEpochMs,syncState) VALUES ('tree','workspace','Activity','primary',0,1,3,1000,'SYNCED')")
            close()
        }

        val before = System.currentTimeMillis()
        helper.runMigrationsAndValidate(TEST_DB_V8, 8, true, T4LDatabase.MIGRATION_7_8).use { db ->
            db.query("SELECT trashedAtEpochMs,purgedAtEpochMs FROM category_trees WHERE id='tree'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(true, cursor.getLong(0) >= before)
                assertEquals(true, cursor.isNull(1))
            }
        }
    }

    @Test
    fun migrate8To9PreservesPendingAndConflictRows() {
        helper.createDatabase(TEST_DB_V9, 8).apply {
            execSQL("INSERT INTO outbox (clientMutationId,clientId,workspaceId,entityType,entityId,operation,baseRevision,payloadJson,createdAtEpochMs,attemptCount) VALUES ('mutation','client','workspace','task','task','upsert',1,'{}',1000,0)")
            execSQL("INSERT INTO conflicts (clientMutationId,workspaceId,entityType,entityId,localPayloadJson,serverPayloadJson,serverRevision,createdAtEpochMs,operation) VALUES ('conflict','workspace','task','task','{}','{}',1,1000,'upsert')")
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB_V9, 9, true, T4LDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT atomicGroupId FROM outbox WHERE clientMutationId='mutation'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(true, cursor.isNull(0))
            }
            db.query("SELECT linkedEventId FROM conflicts WHERE clientMutationId='conflict'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(true, cursor.isNull(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test"
        const val TEST_DB_V4 = "migration-test-v4"
        const val TEST_DB_V5 = "migration-test-v5"
        const val TEST_DB_V6 = "migration-test-v6"
        const val TEST_DB_V7 = "migration-test-v7"
        const val TEST_DB_V8 = "migration-test-v8"
        const val TEST_DB_V9 = "migration-test-v9"
    }
}
