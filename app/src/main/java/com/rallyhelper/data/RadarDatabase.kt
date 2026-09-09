package com.rallyhelper.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity data class RadarSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val calibrationProfileId: String,
)

@Entity data class RallyObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val rallyId: String,
    val observedAtMonotonicMs: Long,
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
    @Query("UPDATE RadarSession SET endedAtEpochMs = :endedAtEpochMs WHERE id = :sessionId")
    suspend fun endSession(sessionId: Long, endedAtEpochMs: Long)
}

@Database(
    entities = [RadarSession::class, RallyObservation::class, DetectorDecisionRecord::class, SafetyAbort::class],
    version = 2,
    exportSchema = true,
)
abstract class RadarDatabase : RoomDatabase() {
    abstract fun radarDao(): RadarDao
}
