package com.rallyhelper.data

import android.content.Context
import androidx.room.Room
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
    ) {
        dao.insertObservation(
            RallyObservation(
                sessionId = sessionId,
                rallyId = rallyId,
                observedAtMonotonicMs = candidate.firstSeenMonotonicMs,
                boss = candidate.bossType.name,
                level = candidate.level,
                participantCount = candidate.participantCount,
                capacity = candidate.capacity,
                countdownSeconds = candidate.remainingSeconds,
                actionable = actionable,
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
            Room.databaseBuilder(context, RadarDatabase::class.java, "radar.db").build(),
        )
    }
}
