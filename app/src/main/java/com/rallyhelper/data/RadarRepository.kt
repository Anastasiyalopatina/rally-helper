package com.rallyhelper.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import radar.vision.DetectorDecision
import radar.vision.RallyCandidate
import radar.vision.RuntimeMode
import kotlinx.coroutines.flow.Flow

class RadarRepository private constructor(private val database: RadarDatabase) {
    private val dao = database.radarDao()

    suspend fun beginSession(calibrationProfileId: String, mode: RuntimeMode): Long = dao.insertSession(
        RadarSession(
            startedAtEpochMs = System.currentTimeMillis(),
            calibrationProfileId = calibrationProfileId,
            mode = mode.name,
        ),
    )

    suspend fun endSession(sessionId: Long, summary: com.rallyhelper.RadarStatus) {
        dao.endSession(
            sessionId,
            System.currentTimeMillis(),
            summary.framesAnalyzed,
            summary.eligible,
            summary.attempts,
            summary.successes,
            summary.failures,
            summary.policySkipped,
        )
    }

    suspend fun recordObservation(
        sessionId: Long,
        rallyId: String,
        candidate: RallyCandidate,
        observedAtMonotonicMs: Long,
        actionable: Boolean,
        selectedDelaySeconds: Int?,
        skipDecision: Boolean?,
        eventType: String = "OBSERVED",
    ) {
        dao.insertObservation(
            RallyObservation(
                sessionId = sessionId,
                rallyId = rallyId,
                observedAtMonotonicMs = observedAtMonotonicMs,
                firstSeenMonotonicMs = candidate.firstSeenMonotonicMs,
                observedAtEpochMs = System.currentTimeMillis(),
                boss = candidate.bossType.name,
                level = candidate.level,
                participantCount = candidate.participantCount,
                capacity = candidate.capacity,
                countdownSeconds = candidate.remainingSeconds,
                actionable = actionable,
                selectedDelaySeconds = selectedDelaySeconds,
                skipDecision = skipDecision,
                joinedState = candidate.joinedState.name,
                eventType = eventType,
            ),
        )
    }

    suspend fun recordDecision(sessionId: Long, decision: DetectorDecision) {
        dao.insertDecision(
            DetectorDecisionRecord(
                sessionId = sessionId,
                observedAtEpochMs = System.currentTimeMillis(),
                kind = decision.kind.name,
                reason = "${decision.rallyId?.value ?: "frame"}:${decision.reason}",
            ),
        )
    }

    suspend fun recordAbort(sessionId: Long, reason: String) {
        dao.insertAbort(SafetyAbort(sessionId = sessionId, observedAtEpochMs = System.currentTimeMillis(), reason = reason))
    }

    fun observeRecentSessions(limit: Int = 25): Flow<List<RadarSession>> = dao.observeRecentSessions(limit)

    fun observeSessionEvents(sessionId: Long): Flow<List<RallyObservation>> = dao.observeSessionEvents(sessionId)

    fun close() = database.close()

    companion object {
        fun create(context: Context): RadarRepository = RadarRepository(
            Room.databaseBuilder(context, RadarDatabase::class.java, "radar.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build(),
        )

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE RallyObservation ADD COLUMN observedAtEpochMs INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE RallyObservation ADD COLUMN selectedDelaySeconds INTEGER")
                database.execSQL("ALTER TABLE RallyObservation ADD COLUMN skipDecision INTEGER")
                database.execSQL("ALTER TABLE RallyObservation ADD COLUMN attempted INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE RallyObservation ADD COLUMN squad TEXT")
                database.execSQL("ALTER TABLE RallyObservation ADD COLUMN travelTimeSeconds INTEGER")
                database.execSQL("ALTER TABLE RallyObservation ADD COLUMN result TEXT")
                database.execSQL("ALTER TABLE RallyObservation ADD COLUMN failureReason TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE RadarSession ADD COLUMN mode TEXT NOT NULL DEFAULT 'RADAR'")
                database.execSQL("ALTER TABLE RadarSession ADD COLUMN framesAnalyzed INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE RadarSession ADD COLUMN eligible INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE RadarSession ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE RadarSession ADD COLUMN successes INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE RadarSession ADD COLUMN failures INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE RadarSession ADD COLUMN policySkipped INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE RallyObservation ADD COLUMN firstSeenMonotonicMs INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE RallyObservation ADD COLUMN joinedState TEXT NOT NULL DEFAULT 'UNKNOWN'")
                database.execSQL("ALTER TABLE RallyObservation ADD COLUMN eventType TEXT NOT NULL DEFAULT 'OBSERVED'")
            }
        }
    }
}
