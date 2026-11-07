package com.trypro.opendms.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: LocalSession)

    @Update
    suspend fun update(session: LocalSession)

    @Query("SELECT * FROM local_session WHERE id = :id")
    suspend fun getById(id: String): LocalSession?

    /** SES-007: [before]より前に始まって終了処理されていない運行。 */
    @Query("SELECT * FROM local_session WHERE status = :status AND startedAtUtcMs < :before")
    suspend fun withStatusStartedBefore(status: String, before: Long): List<LocalSession>

    @Query("DELETE FROM local_session WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 診断画面用。11_DAY_SMARTPHONE_MVP_PLAN.md 3.7: 「診断画面に直近session/eventを表示」。 */
    @Query("SELECT * FROM local_session ORDER BY startedAtUtcMs DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<LocalSession>
}
