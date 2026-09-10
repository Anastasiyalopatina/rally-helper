package com.rallyhelper.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity data class RadarSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val calibrationProfileId: String,
    @ColumnInfo(defaultValue = "'RADAR'") val mode: String = "RADAR",
    @ColumnInfo(defaultValue = "0") val framesAnalyzed: Long = 0,
    @ColumnInfo(defaultValue = "0") val eligible: Long = 0,
    @ColumnInfo(defaultValue = "0") val attempts: Long = 0,
    @ColumnInfo(defaultValue = "0") val successes: Long = 0,
    @ColumnInfo(defaultValue = "0") val failures: Long = 0,
    @ColumnInfo(defaultValue = "0") val policySkipped: Long = 0,
    @ColumnInfo(defaultValue = "0") val shadowWouldAttempts: Long = 0,
    @ColumnInfo(defaultValue = "0") val actualAttempts: Long = 0,
    @ColumnInfo(defaultValue = "0") val actualSuccesses: Long = 0,
    @ColumnInfo(defaultValue = "0") val actualFailures: Long = 0,
)

@Entity data class RallyObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val rallyId: String,
    val observedAtMonotonicMs: Long,
    @ColumnInfo(defaultValue = "0") val firstSeenMonotonicMs: Long,
    @ColumnInfo(defaultValue = "0") val observedAtEpochMs: Long,
    val boss: String,
    val level: Int?,
    val participantCount: Int?,
    val capacity: Int?,
    val countdownSeconds: Int?,
    val actionable: Boolean,
    val selectedDelaySeconds: Int? = null,
    val skipDecision: Boolean? = null,
    @ColumnInfo(defaultValue = "0") val attempted: Boolean = false,
    val squad: String? = null,
    val travelTimeSeconds: Int? = null,
    val result: String? = null,
    val failureReason: String? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val joinedState: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'OBSERVED'") val eventType: String = "OBSERVED",
)

@Entity data class DetectorDecisionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val observedAtEpochMs: Long,
    val kind: String,
    val reason: String,
)

@Entity data class SafetyAbort(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val observedAtEpochMs: Long,
    val reason: String,
)

@Dao interface RadarDao {
    @Insert suspend fun insertSession(value: RadarSession): Long
    @Insert suspend fun insertObservation(value: RallyObservation): Long
    @Insert suspend fun insertDecision(value: DetectorDecisionRecord): Long
    @Insert suspend fun insertAbort(value: SafetyAbort): Long
    @Query(
        "UPDATE RadarSession SET endedAtEpochMs = :endedAtEpochMs, framesAnalyzed = :frames, " +
            "eligible = :eligible, policySkipped = :skipped, shadowWouldAttempts = :shadowWouldAttempts, " +
            "actualAttempts = :actualAttempts, actualSuccesses = :actualSuccesses, " +
            "actualFailures = :actualFailures WHERE id = :sessionId",
    )
    suspend fun endSession(
        sessionId: Long,
        endedAtEpochMs: Long,
        frames: Long,
        eligible: Long,
        skipped: Long,
        shadowWouldAttempts: Long,
        actualAttempts: Long,
        actualSuccesses: Long,
        actualFailures: Long,
    )
    @Query("SELECT * FROM RadarSession ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeRecentSessions(limit: Int = 25): Flow<List<RadarSession>>
    @Query("SELECT * FROM RallyObservation WHERE sessionId = :sessionId ORDER BY observedAtEpochMs DESC")
    fun observeSessionEvents(sessionId: Long): Flow<List<RallyObservation>>
}

@Database(
    entities = [RadarSession::class, RallyObservation::class, DetectorDecisionRecord::class, SafetyAbort::class],
    version = 4,
    exportSchema = true,
)
abstract class RadarDatabase : RoomDatabase() {
    abstract fun radarDao(): RadarDao
}
