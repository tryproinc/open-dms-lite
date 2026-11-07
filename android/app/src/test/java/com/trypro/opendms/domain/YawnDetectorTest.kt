package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [YawnDetector]の単体試験。閾値は実測前の暫定値なので、ここで検証するのは
 * **アルゴリズムの性質**(連続時間と深さの両方で成立する / 1回のあくびを二重に数えない /
 * 会話のような断続的な開口では成立しない / 窓から溢れた回数は落ちる)であって、
 * 閾値そのものの妥当性ではない。
 */
class YawnDetectorTest {

    private val settings = DayMvpSettings.DAY_MVP0_DEFAULT

    /** [jawOpen]の値を[durationMs]の間、[stepMs]間隔で与える。最後のYawnStateを返す。 */
    private fun feed(
        detector: YawnDetector,
        startMs: Long,
        durationMs: Long,
        jawOpen: Float?,
        stepMs: Long = 50L,
    ): Pair<Long, YawnState> {
        var t = startMs
        var state = YawnState(0, 0L, 0f, false, false)
        val end = startMs + durationMs
        while (t <= end) {
            state = detector.update(t, jawOpen)
            t += stepMs
        }
        return t to state
    }

    @Test
    fun `閾値以上が規定時間続くとあくび1回が成立する`() {
        val detector = YawnDetector(settings)
        val (_, state) = feed(detector, 0L, settings.yawnMinDurationMs + 200L, 0.8f)

        assertThat(state.countInWindow).isEqualTo(1)
    }

    @Test
    fun `規定時間に足りない開口ではあくびが成立しない`() {
        val detector = YawnDetector(settings)
        val (_, state) = feed(detector, 0L, settings.yawnMinDurationMs - 300L, 0.8f)

        assertThat(state.countInWindow).isEqualTo(0)
    }

    /**
     * 2026-10-16回帰(Lite 01484338): 口を閉じたままパンを噛んでいた間に`jawOpen`が0.6〜0.8まで上がり、
     * 1.0〜1.07秒続いて10回中9回があくびとして数えられた。下限を1.15秒、2026-10-17に利用者の指示で1.5秒にした。
     */
    @Test
    fun `咀嚼で上がった開口や1_33秒の開口は数えず1_6秒は数える`() {
        val chewing = feed(YawnDetector(settings), 0L, 1_070L, 0.7f).second
        val short = feed(YawnDetector(settings), 0L, 1_330L, 0.7f).second
        val yawn = feed(YawnDetector(settings), 0L, 1_600L, 0.7f).second

        assertThat(chewing.countInWindow).isEqualTo(0)
        assertThat(short.countInWindow).isEqualTo(0)
        assertThat(yawn.countInWindow).isEqualTo(1)
    }

    @Test
    fun `閾値未満の開口ではあくびが成立しない`() {
        val detector = YawnDetector(settings)
        val (_, state) = feed(detector, 0L, settings.yawnMinDurationMs * 3, settings.yawnJawOpenThreshold - 0.05f)

        assertThat(state.countInWindow).isEqualTo(0)
    }

    @Test
    fun `1回の長いあくびを毎フレーム二重に数えない`() {
        val detector = YawnDetector(settings)
        // 成立時間の5倍だけ開けっぱなしにしても、episodeは1つなので1回のまま。
        val (_, state) = feed(detector, 0L, settings.yawnMinDurationMs * 5, 0.9f)

        assertThat(state.countInWindow).isEqualTo(1)
    }

    @Test
    fun `会話のような断続的な開閉ではあくびが成立しない`() {
        val detector = YawnDetector(settings)
        var t = 0L
        var state = YawnState(0, 0L, 0f, false, false)
        // 200ms開けて400ms閉じる(=閾値超えが1.2秒続かない)を30秒ぶん繰り返す。
        // 閉口区間400msはqualityGapGraceMs(500ms)以下だが、閉口はgap(欠損)ではなく
        // 「条件が偽」なので、gap猶予では繋がらないことを確かめる。
        repeat(50) {
            repeat(4) { state = detector.update(t, 0.85f); t += 50L }
            repeat(8) { state = detector.update(t, 0.10f); t += 50L }
        }

        assertThat(state.countInWindow).isEqualTo(0)
    }

    @Test
    fun `短い品質欠損はあくびのepisodeを分断しない`() {
        val detector = YawnDetector(settings)
        var t = 0L
        // 600ms開口 → 300ms欠損(null) → 900ms開口。欠損は猶予(500ms)以内なので
        // 合計1500ms > 1200msとして1回成立する。
        repeat(12) { detector.update(t, 0.85f); t += 50L }
        repeat(6) { detector.update(t, null); t += 50L }
        var state = YawnState(0, 0L, 0f, false, false)
        repeat(18) { state = detector.update(t, 0.85f); t += 50L }

        assertThat(state.countInWindow).isEqualTo(1)
    }

    @Test
    fun `猶予を超える欠損はepisodeを分断する`() {
        val detector = YawnDetector(settings)
        var t = 0L
        // 1000ms開口 → 800ms欠損(猶予500ms超) → 1000ms開口。
        // どちらの区間も単独では1200msに届かないので成立しない。
        repeat(20) { detector.update(t, 0.85f); t += 50L }
        repeat(16) { detector.update(t, null); t += 50L }
        var state = YawnState(0, 0L, 0f, false, false)
        repeat(20) { state = detector.update(t, 0.85f); t += 50L }

        assertThat(state.countInWindow).isEqualTo(0)
    }

    @Test
    fun `窓内で規定回数に達すると頻発と判定する`() {
        val detector = YawnDetector(settings)
        var t = 0L
        var state = YawnState(0, 0L, 0f, false, false)
        repeat(settings.yawnMinCountInWindow) {
            val (next, s) = feed(detector, t, settings.yawnMinDurationMs + 200L, 0.85f)
            state = s
            // あくびの間は口を閉じる(episodeを終わらせる)。
            val (afterClose, _) = feed(detector, next, 1_000L, 0.05f)
            t = afterClose
        }

        assertThat(state.countInWindow).isEqualTo(settings.yawnMinCountInWindow)
        assertThat(state.isFrequent).isTrue()
    }

    @Test
    fun `規定回数に1回足りなければ頻発と判定しない`() {
        val detector = YawnDetector(settings)
        var t = 0L
        var state = YawnState(0, 0L, 0f, false, false)
        repeat(settings.yawnMinCountInWindow - 1) {
            val (next, s) = feed(detector, t, settings.yawnMinDurationMs + 200L, 0.85f)
            state = s
            val (afterClose, _) = feed(detector, next, 1_000L, 0.05f)
            t = afterClose
        }

        assertThat(state.isFrequent).isFalse()
    }

    @Test
    fun `窓から外れた古いあくびは回数から落ちる`() {
        val detector = YawnDetector(settings)
        val (afterFirst, first) = feed(detector, 0L, settings.yawnMinDurationMs + 200L, 0.85f)
        assertThat(first.countInWindow).isEqualTo(1)

        // 窓(既定10分)を超えて口を閉じたまま経過させる。
        val (_, later) = feed(
            detector,
            afterFirst + settings.yawnWindowMs + 1_000L,
            500L,
            0.05f,
        )
        assertThat(later.countInWindow).isEqualTo(0)
    }

    @Test
    fun `resetで回数と進行中のepisodeが消える`() {
        val detector = YawnDetector(settings)
        feed(detector, 0L, settings.yawnMinDurationMs + 200L, 0.85f)
        detector.reset()

        val state = detector.update(10_000L, 0.05f)
        assertThat(state.countInWindow).isEqualTo(0)
        assertThat(state.currentOpenDurationMs).isEqualTo(0L)
    }

    @Test
    fun `浅く長い開口は深さの条件で落とす`() {
        // 2026-09-23の実車で実際に数えられていた形。閾値(0.45)は超えるが
        // ピークが0.50までしか上がらない開口が3.6秒続く(会話・口の半開き)。
        val detector = YawnDetector(settings)
        val shallow = (settings.yawnJawOpenThreshold + settings.yawnMinPeakJawOpen) / 2f
        check(shallow >= settings.yawnJawOpenThreshold && shallow < settings.yawnMinPeakJawOpen)

        val (_, state) = feed(detector, 0L, settings.yawnMinDurationMs * 3, shallow)

        assertThat(state.countInWindow).isEqualTo(0)
    }

    @Test
    fun `ピークが連続時間より後に来ても成立する`() {
        // ゆっくり開くあくび。連続時間を満たした時点ではまだ浅く、そのあと深くなる。
        val detector = YawnDetector(settings)
        val shallow = (settings.yawnJawOpenThreshold + settings.yawnMinPeakJawOpen) / 2f
        var t = 0L
        val (next, mid) = feed(detector, t, settings.yawnMinDurationMs + 200L, shallow)
        assertThat(mid.countInWindow).isEqualTo(0)

        t = next
        val (_, state) = feed(detector, t, 300L, settings.yawnMinPeakJawOpen + 0.05f)

        assertThat(state.countInWindow).isEqualTo(1)
    }

    @Test
    fun `深さの条件を満たさないまま口を閉じるとピークは持ち越さない`() {
        val detector = YawnDetector(settings)
        val shallow = (settings.yawnJawOpenThreshold + settings.yawnMinPeakJawOpen) / 2f
        // 深いが短い開口 → 閉口 → 浅く長い開口。ピークが次のepisodeへ漏れないことを確かめる。
        val (afterDeep, _) = feed(detector, 0L, settings.yawnMinDurationMs / 2, 0.9f)
        val (afterClose, _) = feed(detector, afterDeep, 1_000L, 0.05f)
        val (_, state) = feed(detector, afterClose, settings.yawnMinDurationMs * 2, shallow)

        assertThat(state.countInWindow).isEqualTo(0)
    }
}
