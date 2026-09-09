package com.rallyhelper.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RadarMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RadarDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_preservesExistingRecords() {
        createV1("migration-1-2")
        helper.runMigrationsAndValidate("migration-1-2", 2, true, RadarRepository.MIGRATION_1_2).use { db ->
            assertPreserved(db, "old-v1")
        }
    }

    @Test
    fun migrate2To3_preservesExistingRecords() {
        helper.createDatabase("migration-2-3", 2).use { db -> insertRecords(db, "old-v2", version = 2) }
        helper.runMigrationsAndValidate("migration-2-3", 3, true, RadarRepository.MIGRATION_2_3).use { db ->
            assertPreserved(db, "old-v2")
            db.query("SELECT mode, framesAnalyzed FROM RadarSession WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("RADAR", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
            }
        }
    }

    @Test
    fun migrate1To3_preservesExistingRecords() {
        createV1("migration-1-3")
        helper.runMigrationsAndValidate(
            "migration-1-3",
            3,
            true,
            RadarRepository.MIGRATION_1_2,
            RadarRepository.MIGRATION_2_3,
        ).use { db ->
            assertPreserved(db, "old-v1")
            db.query("SELECT eventType, joinedState FROM RallyObservation WHERE id = 1").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("OBSERVED", cursor.getString(0))
                assertEquals("UNKNOWN", cursor.getString(1))
            }
        }
    }

    private fun createV1(name: String) {
        helper.createDatabase(name, 1).use { db -> insertRecords(db, "old-v1", version = 1) }
    }

    private fun insertRecords(db: androidx.sqlite.db.SupportSQLiteDatabase, rallyId: String, version: Int) {
        db.execSQL(
            "INSERT INTO RadarSession (id, startedAtEpochMs, endedAtEpochMs, calibrationProfileId) " +
                "VALUES (1, 1000, NULL, 'legacy-profile')",
        )
        val extraColumns = if (version >= 2) ", observedAtEpochMs, attempted" else ""
        val extraValues = if (version >= 2) ", 2000, 0" else ""
        db.execSQL(
            "INSERT INTO RallyObservation " +
                "(id, sessionId, rallyId, observedAtMonotonicMs, boss, level, participantCount, capacity, " +
                "countdownSeconds, actionable$extraColumns) VALUES " +
                "(1, 1, '$rallyId', 123, 'TARGET', 10, 1, 5, 50, 1$extraValues)",
        )
    }

    private fun assertPreserved(db: androidx.sqlite.db.SupportSQLiteDatabase, rallyId: String) {
        db.query("SELECT calibrationProfileId FROM RadarSession WHERE id = 1").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("legacy-profile", cursor.getString(0))
        }
        db.query("SELECT rallyId, participantCount, capacity FROM RallyObservation WHERE id = 1").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(rallyId, cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(5, cursor.getInt(2))
        }
    }
}
