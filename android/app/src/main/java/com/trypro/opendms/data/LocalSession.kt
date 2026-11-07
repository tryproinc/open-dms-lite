package com.trypro.opendms.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 1回の運行(較正済み状態からMONITORING開始〜終了まで)。
 * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.7: 座標・画像・動画・顔landmark・氏名を一切持たせない。
 */
@Entity(tableName = DbTables.SESSION)
data class LocalSession(
    @PrimaryKey val id: String,
    val startedAtUtcMs: Long,
    val endedAtUtcMs: Long?,
    /** "ACTIVE" | "COMPLETED" | "ABORTED"。Room 2.8のenumカラムに頼らず文字列で持つ(シンプルさ優先)。 */
    val status: String,
    val monitoringMs: Long,
    val validMonitoringMs: Long,
    val appVersion: String,
    val modelVersion: String,
    val settingsVersion: String,
    val deviceModel: String,
    val osVersion: String,
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_COMPLETED = "COMPLETED"

        /**
         * 異常終了した運行(SES-007)。アプリが終了処理を経ずに落ちた/入れ替えられたもので、
         * 次回起動時に[SessionRepository.closeAbandonedSessions]が付ける。
         * `monitoringMs`は終了処理でしか書かないため0のまま(実際の監視時間は不明)。
         */
        const val STATUS_ABORTED = "ABORTED"
    }
}
