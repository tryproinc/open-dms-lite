package com.trypro.opendms.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: LocalEvent)

    @Query("SELECT * FROM local_event WHERE sessionId = :sessionId ORDER BY startedAtUtcMs ASC")
    suspend fun forSession(sessionId: String): List<LocalEvent>

    @Query("SELECT COUNT(*) FROM local_event WHERE sessionId = :sessionId AND type = :type AND severity = :severity")
    suspend fun countByTypeAndSeverity(sessionId: String, type: String, severity: String): Int
}
