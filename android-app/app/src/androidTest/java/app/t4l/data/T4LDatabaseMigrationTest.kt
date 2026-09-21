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

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
