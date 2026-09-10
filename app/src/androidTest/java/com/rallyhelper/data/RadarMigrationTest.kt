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

    @Test
    fun migrate3To4_splitsShadowAndActualCounters() {
        helper.createDatabase("migration-3-4", 3).use { db ->
            insertRecords(db, "old-v3", version = 3)
            db.execSQL("UPDATE RadarSession SET mode = 'SHADOW_AUTO', attempts = 7, successes = 0, failures = 0 WHERE id = 1")
        }
        helper.runMigrationsAndValidate("migration-3-4", 4, true, RadarRepository.MIGRATION_3_4).use { db ->
            assertPreserved(db, "old-v3")
            db.query(
                "SELECT shadowWouldAttempts, actualAttempts, actualSuccesses, actualFailures FROM RadarSession WHERE id = 1",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals(0L, cursor.getLong(2))
                assertEquals(0L, cursor.getLong(3))
            }
        }
    }

    @Test
    fun migrate1To4_preservesExistingRecords() {
        createV1("migration-1-4")
        helper.runMigrationsAndValidate(
            "migration-1-4",
            4,
            true,
            RadarRepository.MIGRATION_1_2,
            RadarRepository.MIGRATION_2_3,
            RadarRepository.MIGRATION_3_4,
        ).use { db -> assertPreserved(db, "old-v1") }
    }

    @Test
    fun migrate4To5_renamesOneTapOpenCountersWithoutInventingJoins() {
        helper.createDatabase("migration-4-5", 4).use { db ->
            insertRecords(db, "old-v4", version = 4)
            db.execSQL(
                "UPDATE RadarSession SET mode = 'ONE_TAP', actualAttempts = 3, " +
                    "actualSuccesses = 2, actualFailures = 1 WHERE id = 1",
            )
        }
        helper.runMigrationsAndValidate("migration-4-5", 5, true, RadarRepository.MIGRATION_4_5).use { db ->
            db.query(
                "SELECT oneTapOpenAttempts, oneTapOpenSuccesses, oneTapOpenFailures, " +
                    "joinAttempts, joinSuccesses, joinFailures FROM RadarSession WHERE id = 1",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(3L, cursor.getLong(0))
                assertEquals(2L, cursor.getLong(1))
                assertEquals(1L, cursor.getLong(2))
                assertEquals(0L, cursor.getLong(3))
                assertEquals(0L, cursor.getLong(4))
                assertEquals(0L, cursor.getLong(5))
            }
        }
    }

    @Test
    fun migrate1To5_preservesExistingRecords() {
        createV1("migration-1-5")
        helper.runMigrationsAndValidate(
            "migration-1-5",
            5,
            true,
            RadarRepository.MIGRATION_1_2,
            RadarRepository.MIGRATION_2_3,
            RadarRepository.MIGRATION_3_4,
            RadarRepository.MIGRATION_4_5,
        ).use { db -> assertPreserved(db, "old-v1") }
    }

    @Test
    fun migrate5To6_addsSelectionAndSendCountersWithoutInventingResults() {
        helper.createDatabase("migration-5-6", 5).use { db -> insertRecords(db, "old-v5", version = 5) }
        helper.runMigrationsAndValidate("migration-5-6", 6, true, RadarRepository.MIGRATION_5_6).use { db ->
            assertPreserved(db, "old-v5")
            db.query(
                "SELECT squadSelectionAttempts, squadSelectionSuccesses, squadSelectionFailures, " +
                    "sendAttempts, sendVerifiedSuccesses, sendFailures FROM RadarSession WHERE id = 1",
            ).use { cursor ->
                check(cursor.moveToFirst())
                repeat(6) { assertEquals(0L, cursor.getLong(it)) }
            }
        }
    }

    @Test
    fun migrate1To6_preservesExistingRecords() {
        createV1("migration-1-6")
        helper.runMigrationsAndValidate(
            "migration-1-6",
            6,
            true,
            RadarRepository.MIGRATION_1_2,
            RadarRepository.MIGRATION_2_3,
            RadarRepository.MIGRATION_3_4,
            RadarRepository.MIGRATION_4_5,
            RadarRepository.MIGRATION_5_6,
        ).use { db -> assertPreserved(db, "old-v1") }
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
