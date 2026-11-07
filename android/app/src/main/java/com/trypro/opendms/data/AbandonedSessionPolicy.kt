package com.trypro.opendms.data

/** 異常終了した運行1件の片付け方。 */
sealed interface AbandonedSessionAction {
    /** 発報の記録が無い。残す価値が無いので運行の行ごと消す。 */
    data object Delete : AbandonedSessionAction

    /** 発報の記録がある。[LocalSession.STATUS_ABORTED]にして残す。 */
    data class Abort(val endedAtUtcMs: Long) : AbandonedSessionAction
}

/**
 * SES-007: 異常終了した運行(`ACTIVE`のまま残ったもの)をどう片付けるか。
 *
 * **発報の記録がある運行は消さない。** 2026-09-26時点で端末に残っていた3件のうち2件は
 * 実際の閉眼警告を持っていた。落ちる直前の警告こそ後から確かめたい記録なので、
 * 終了扱い(`ABORTED`)にして残す。記録が無い運行だけを消す(2026-09-26 利用者の決定)。
 *
 * 終了時刻は**最後の発報の終わり**で推定する。実際の終了はそれより後だが、
 * 端末のDBが知っている最後の事実はそこまでで、推測で伸ばさない。
 *
 * 診断CSV・参考録画はこの判定の対象外(DBの行だけを扱う)。
 */
object AbandonedSessionPolicy {
    fun decide(session: LocalSession, events: List<LocalEvent>): AbandonedSessionAction {
        if (events.isEmpty()) return AbandonedSessionAction.Delete
        val lastActivity = events.maxOf { maxOf(it.startedAtUtcMs, it.endedAtUtcMs) }
        return AbandonedSessionAction.Abort(endedAtUtcMs = maxOf(lastActivity, session.startedAtUtcMs))
    }
}
