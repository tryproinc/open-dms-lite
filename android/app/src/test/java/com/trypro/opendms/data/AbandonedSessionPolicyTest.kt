package com.trypro.opendms.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** SES-007 の片付け方([AbandonedSessionPolicy])の単体試験。 */
class AbandonedSessionPolicyTest {

    private val session = LocalSession(
        id = "s1",
        startedAtUtcMs = 1_000L,
        endedAtUtcMs = null,
        status = LocalSession.STATUS_ACTIVE,
        monitoringMs = 0L,
        validMonitoringMs = 0L,
        appVersion = "test",
        modelVersion = "test",
        settingsVersion = "test",
        deviceModel = "test",
        osVersion = "test",
    )

    private fun event(startedAt: Long, endedAt: Long) = LocalEvent(
        id = "e$startedAt",
        sessionId = session.id,
        type = "EYE_CLOSURE",
        severity = "WARNING",
        startedAtUtcMs = startedAt,
        endedAtUtcMs = endedAt,
        durationMs = endedAt - startedAt,
        qualityFlags = "",
        confidence = null,
        appVersion = "test",
        modelVersion = "test",
        settingsVersion = "test",
    )

    @Test
    fun `発報の記録が無い運行は削除する`() {
        assertThat(AbandonedSessionPolicy.decide(session, emptyList()))
            .isEqualTo(AbandonedSessionAction.Delete)
    }

    /** 落ちる直前の警告こそ後から確かめたい記録なので、消さずに終了扱いで残す。 */
    @Test
    fun `発報の記録がある運行は消さずに最後の発報の終わりで終了扱いにする`() {
        val action = AbandonedSessionPolicy.decide(
            session,
            listOf(event(5_000L, 6_500L), event(2_000L, 3_000L)),
        )

        assertThat(action).isEqualTo(AbandonedSessionAction.Abort(endedAtUtcMs = 6_500L))
    }

    /** 端末の時計が戻った等で発報が開始より前に見えても、終了が開始より前にはならない。 */
    @Test
    fun `終了時刻は開始時刻より前にしない`() {
        val action = AbandonedSessionPolicy.decide(session, listOf(event(500L, 800L)))

        assertThat(action).isEqualTo(AbandonedSessionAction.Abort(endedAtUtcMs = session.startedAtUtcMs))
    }
}
