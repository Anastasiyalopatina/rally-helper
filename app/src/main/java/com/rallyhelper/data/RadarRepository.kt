package com.rallyhelper.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import radar.vision.DetectorDecision
import radar.vision.RallyCandidate

class RadarRepository private constructor(private val database: RadarDatabase) {
    private val dao = database.radarDao()

    suspend fun beginSession(calibrationProfileId: String): Long = dao.insertSession(
        RadarSession(startedAtEpochMs = System.currentTimeMillis(), calibrationProfileId = calibrationProfileId),
    )

    suspend fun endSession(sessionId: Long) {
        dao.endSession(sessionId, System.currentTimeMillis())
    }

    suspend fun recordObservation(
        sessionId: Long,
        rallyId: String,
        candidate: RallyCandidate,
        actionable: Boolean,
        selectedDelaySeconds: Int?,
        skipDecision: Boolean?,
    ) {
        dao.insertObservation(
            RallyObservation(
                sessionId = sessionId,
                rallyId = rallyId,
                observedAtMonotonicMs = candidate.firstSeenMonotonicMs,
                observedAtEpochMs = System.currentTimeMillis(),
                boss = candidate.bossType.name,
                level = candidate.level,
                participantCount = candidate.participantCount,
                capacity = candidate.capacity,
                countdownSeconds = candidate.remainingSeconds,
                actionable = actionable,
                selectedDelaySeconds = selectedDelaySeconds,
                skipDecision = skipDecision,
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

    fun close() = database.close()

    companion object {
        fun create(context: Context): RadarRepository = RadarRepository(
            Room.databaseBuilder(context, RadarDatabase::class.java, "radar.db")
                .addMigrations(MIGRATION_1_2)
                .build(),
        )

        private val MIGRATION_1_2 = object : Migration(1, 2) {
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
    }
}
