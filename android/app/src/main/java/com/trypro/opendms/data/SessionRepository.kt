package com.trypro.opendms.data

import android.content.Context
import android.os.Build
import com.trypro.opendms.BuildConfig
import com.trypro.opendms.domain.EventLocation
import com.trypro.opendms.domain.AlertType
import com.trypro.opendms.domain.FiredAlert
import com.trypro.opendms.vision.QualityFlag
import java.util.UUID

/**
 * 運行(LocalSession)とイベント(LocalEvent)の記録・集計を行う。
 * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.7: 座標・画像・動画・顔landmark・氏名は一切扱わない
 * ([LocalSession]/[LocalEvent]自体がそれらのフィールドを持たない設計)。
 *
 * SES-007(異常終了した運行の復旧)は[closeAbandonedSessions](2026-09-26)。
 */
class SessionRepository(context: Context) {
    private val db = AppDatabase.getInstance(context)

    suspend fun startSession(modelVersion: String, settingsVersion: String): LocalSession {
        val session = LocalSession(
            id = UUID.randomUUID().toString(),
            startedAtUtcMs = System.currentTimeMillis(),
            endedAtUtcMs = null,
            status = LocalSession.STATUS_ACTIVE,
            monitoringMs = 0L,
            validMonitoringMs = 0L,
            appVersion = BuildConfig.VERSION_NAME,
            modelVersion = modelVersion,
            settingsVersion = settingsVersion,
            deviceModel = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
        )
        db.sessionDao().insert(session)
        return session
    }

    suspend fun endSession(session: LocalSession, monitoringMs: Long, validMonitoringMs: Long): LocalSession {
        val updated = session.copy(
            endedAtUtcMs = System.currentTimeMillis(),
            status = LocalSession.STATUS_COMPLETED,
            monitoringMs = monitoringMs,
            validMonitoringMs = validMonitoringMs,
        )
        db.sessionDao().update(updated)
        return updated
    }

    /**
     * SES-007: 異常終了した運行を片付ける。アプリ起動時に1回呼ぶ。
     *
     * 運行中かどうかは画面の状態(メモリ)にしか無いため、プロセスが消えた時点で`ACTIVE`の行は
     * 二度と終了処理されない。[launchedAtUtcMs]より前に始まったものだけを対象にし、
     * この起動で始めた運行を巻き込まないようにする。片付け方は[AbandonedSessionPolicy]。
     *
     * @return 終了扱いにした件数と削除した件数。
     */
    suspend fun closeAbandonedSessions(launchedAtUtcMs: Long): Pair<Int, Int> {
        var aborted = 0
        var deleted = 0
        db.sessionDao().withStatusStartedBefore(LocalSession.STATUS_ACTIVE, launchedAtUtcMs).forEach { session ->
            when (val action = AbandonedSessionPolicy.decide(session, db.eventDao().forSession(session.id))) {
                AbandonedSessionAction.Delete -> {
                    db.sessionDao().deleteById(session.id)
                    deleted++
                }
                is AbandonedSessionAction.Abort -> {
                    db.sessionDao().update(
                        session.copy(status = LocalSession.STATUS_ABORTED, endedAtUtcMs = action.endedAtUtcMs),
                    )
                    aborted++
                }
            }
        }
        return aborted to deleted
    }

    /** この運行の発報を時刻の昇順で返す(結果画面とCSV書き出しで使う)。 */
    suspend fun eventsFor(sessionId: String): List<LocalEvent> =
        db.eventDao().forSession(sessionId)

    suspend fun recordAlertEvent(
        sessionId: String,
        fired: FiredAlert,
        durationMs: Long,
        atWallClockUtcMs: Long,
        qualityFlags: Set<QualityFlag>,
        confidence: Float?,
        modelVersion: String,
        settingsVersion: String,
        speedKph: Float? = null,
        location: EventLocation = EventLocation.NONE,
    ) {
        insertEvent(
            sessionId = sessionId,
            type = fired.type.name,
            severity = fired.severity.name,
            durationMs = durationMs,
            atWallClockUtcMs = atWallClockUtcMs,
            qualityFlags = qualityFlags,
            confidence = confidence,
            modelVersion = modelVersion,
            settingsVersion = settingsVersion,
            speedKph = speedKph,
            location = location,
        )
    }

    suspend fun recordUnavailableEvent(
        sessionId: String,
        durationMs: Long,
        atWallClockUtcMs: Long,
        modelVersion: String,
        settingsVersion: String,
        speedKph: Float? = null,
        location: EventLocation = EventLocation.NONE,
    ) {
        insertEvent(
            sessionId = sessionId,
            type = UNAVAILABLE_TYPE,
            severity = UNAVAILABLE_SEVERITY,
            durationMs = durationMs,
            atWallClockUtcMs = atWallClockUtcMs,
            qualityFlags = emptySet(),
            confidence = null,
            modelVersion = modelVersion,
            settingsVersion = settingsVersion,
            speedKph = speedKph,
            location = location,
        )
    }

    private suspend fun insertEvent(
        sessionId: String,
        type: String,
        severity: String,
        durationMs: Long,
        atWallClockUtcMs: Long,
        qualityFlags: Set<QualityFlag>,
        confidence: Float?,
        modelVersion: String,
        settingsVersion: String,
        speedKph: Float? = null,
        location: EventLocation = EventLocation.NONE,
    ) {
        db.eventDao().insert(
            LocalEvent(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                type = type,
                severity = severity,
                startedAtUtcMs = atWallClockUtcMs - durationMs,
                endedAtUtcMs = atWallClockUtcMs,
                durationMs = durationMs,
                qualityFlags = qualityFlags.joinToString(",") { it.name },
                confidence = confidence,
                appVersion = BuildConfig.VERSION_NAME,
                modelVersion = modelVersion,
                settingsVersion = settingsVersion,
                speedKph = speedKph,
                latitude = location.latitude,
                longitude = location.longitude,
                locationAccuracyM = location.accuracyM,
                locationFixedAtUtcMs = location.fixedAtUtcMs,
                locationProvider = location.provider,
            ),
        )
    }

    suspend fun summarize(session: LocalSession): SessionSummary {
        val dao = db.eventDao()
        return SessionSummary(
            monitoringMs = session.monitoringMs,
            validMonitoringMs = session.validMonitoringMs,
            eyeClosureCautionCount = dao.countByTypeAndSeverity(session.id, AlertType.EYE_CLOSURE.name, "CAUTION"),
            eyeClosureWarningCount = dao.countByTypeAndSeverity(session.id, AlertType.EYE_CLOSURE.name, "WARNING"),
            distractionWarningCount = dao.countByTypeAndSeverity(session.id, AlertType.DISTRACTION.name, "WARNING"),
            unavailableCount = dao.countByTypeAndSeverity(session.id, UNAVAILABLE_TYPE, UNAVAILABLE_SEVERITY),
        )
    }

    private companion object {
        const val UNAVAILABLE_TYPE = "UNAVAILABLE"
        const val UNAVAILABLE_SEVERITY = "NONE"
    }
}
