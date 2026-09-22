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

    private companion object {
        const val TEST_DB = "migration-test"
        const val TEST_DB_V4 = "migration-test-v4"
        const val TEST_DB_V5 = "migration-test-v5"
    }
}
