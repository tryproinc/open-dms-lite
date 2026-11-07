package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 「こっくり」検出([NoddingDetector])のL0テスト。
 * 期待値は2026-08-29の実機ログ(session_70b193d0)の実測特性に合わせている:
 * こっくりの振幅は約3〜5°、半周期は約0.7〜2.6秒、その間の平均開眼比は0.56〜0.65。
 */
class NoddingDetectorTest {

    private val settings = DayMvpSettings.DAY_MVP0_DEFAULT

    /** [amplitudeDeg]の振幅・[halfPeriodMs]の半周期で頭部を上下させ、開眼比[eyeOpen]を与える。 */
    private fun feedNods(
        detector: NoddingDetector,
        nods: Int,
        amplitudeDeg: Float = 8f,
        halfPeriodMs: Long = 1000L,
        eyeOpen: Float = 0.6f,
        stepMs: Long = 50L,
        startMs: Long = 0L,
        /** あくび中かどうか。閾値以上を与えると、こっくりの判定材料が取り込まれなくなる。 */
        jawOpen: Float? = null,
        /** 相対yaw。nullなら渡さない(候補判定はyaw不明では成立しない)。 */
        yaw: ((Long) -> Float)? = null,
    ): NoddingState {
        var t = startMs
        var state = NoddingState(0, null, false)
        // 1 nod = 下げて戻す = 反転2回
        repeat(nods * 2) { half ->
            val from = if (half % 2 == 0) 0f else amplitudeDeg
            val to = if (half % 2 == 0) amplitudeDeg else 0f
            val steps = (halfPeriodMs / stepMs).toInt().coerceAtLeast(1)
            repeat(steps) { i ->
                val pitch = from + (to - from) * (i + 1) / steps
                state = detector.update(t, pitch, eyeOpen, jawOpen, yaw?.invoke(t))
                t += stepMs
            }
        }
        return state
    }

    @Test
    fun `振幅8度のこっくりを繰り返すとこっくり判定になる`() {
        val detector = NoddingDetector(settings)
        // ZigZag法では最初の片振りが基準確立に使われるため、n回のこっくりで反転(2n-1)回になる。
        val state = feedNods(detector, nods = 4)
        assertThat(state.reversalCount).isAtLeast(settings.noddingMinReversals)
        assertThat(state.isNodding).isTrue()
    }

    @Test
    fun `こっくりしていても目が開いていればこっくり判定にしない(会話中の頷き等を除外)`() {
        val detector = NoddingDetector(settings)
        val state = feedNods(detector, nods = 5, eyeOpen = 0.95f)
        assertThat(state.reversalCount).isAtLeast(settings.noddingMinReversals)
        assertThat(state.isNodding).isFalse()
    }

    @Test
    fun `振幅が閾値未満の微小な揺れは反転として数えない`() {
        val detector = NoddingDetector(settings)
        val state = feedNods(detector, nods = 6, amplitudeDeg = 4f)
        assertThat(state.reversalCount).isEqualTo(0)
        assertThat(state.isNodding).isFalse()
    }

    @Test
    fun `一度だけ大きく下を向いて戻す動作(脇見)ではこっくり判定にしない`() {
        val detector = NoddingDetector(settings)
        val state = feedNods(detector, nods = 1, amplitudeDeg = 40f, halfPeriodMs = 3000L)
        assertThat(state.reversalCount).isLessThan(settings.noddingMinReversals)
        assertThat(state.isNodding).isFalse()
    }

    @Test
    fun `監視窓を過ぎた古い反転は数えなくなる`() {
        val detector = NoddingDetector(settings)
        val nodding = feedNods(detector, nods = 3)
        assertThat(nodding.isNodding).isTrue()

        // 窓(既定12秒)を十分に超える間、頭部を静止させる。
        var t = 20_000L
        var state = nodding
        while (t < 40_000L) {
            state = detector.update(t, 0f, 0.6f)
            t += 100L
        }
        assertThat(state.reversalCount).isEqualTo(0)
        assertThat(state.isNodding).isFalse()
    }

    @Test
    fun `resetで内部状態が初期化される`() {
        val detector = NoddingDetector(settings)
        assertThat(feedNods(detector, nods = 3).isNodding).isTrue()
        detector.reset()
        val state = detector.update(50_000L, 0f, 0.6f)
        assertThat(state.reversalCount).isEqualTo(0)
        assertThat(state.isNodding).isFalse()
    }

    /**
     * 2026-08-31実機の回帰: あくびそのものが頭を大きく動かす(頭を反らして戻す)ため、
     * こっくりとして誤検出されていた。実測ではこっくり成立フレームの94%があくびの±3秒以内、
     * 頭部pitchの可動域はあくび中45.3°に対しあくび外11.4°だった。
     * ASV基本設計書でもD4(あくびが頻発)とD5(頭が前後に倒れる)は別の観察指標であり、
     * 同じ動作から二重に発報すべきではない。
     */
    @Test
    fun `あくび中の頭の動きはこっくりとして数えない`() {
        val detector = NoddingDetector(settings)
        // 口を大きく開けたまま、こっくり相当の大きな上下動を繰り返す。
        val state = feedNods(detector, nods = 5, jawOpen = 0.85f)
        assertThat(state.reversalCount).isEqualTo(0)
        assertThat(state.isNodding).isFalse()
    }

    @Test
    fun `あくびが終わって抑制時間を過ぎればまた数える`() {
        val detector = NoddingDetector(settings)
        feedNods(detector, nods = 3, jawOpen = 0.85f)
        // 口を閉じ、抑制時間(既定3秒)を十分に過ぎるまで静止させる。
        var t = 100_000L
        repeat(10) { detector.update(t, 0f, 0.5f, 0.05f); t += 500L }
        // 以後は通常どおり反転を数える。
        var state = NoddingState(0, null, false)
        repeat(10) { half ->
            val target = if (half % 2 == 0) 0f else 8f
            repeat(5) { state = detector.update(t, target, 0.5f, 0.05f); t += 100L }
        }
        assertThat(state.reversalCount).isAtLeast(settings.noddingMinReversals)
    }

    // ---- 開眼こっくり候補(2026-09-11。無音で記録するだけ) ----

    /**
     * 目を開けたまま(0.9)大きく(30°)前後にだけうなずく → こっくりではないが**候補**になる。
     * 2026-09-11の机上試験がまさにこれで、反転9回・窓平均開眼0.85で何も鳴らなかった。
     */
    @Test
    fun `目が開いていても大きな前後のうなずきは候補になる`() {
        val detector = NoddingDetector(settings)
        val state = feedNods(detector, nods = 4, amplitudeDeg = 30f, eyeOpen = 0.9f, yaw = { 0f })
        assertThat(state.isNodding).isFalse()
        assertThat(state.pitchRangeDeg).isAtLeast(settings.noddingCandidateMinPitchRangeDeg)
        assertThat(state.yawRangeDeg).isLessThan(settings.noddingCandidateMaxYawRangeDeg)
        assertThat(state.isOpenEyeCandidate).isTrue()
    }

    /**
     * **これが弁別の要。**同じ首の動きでも左右を伴えば「ミラーや周囲を見ている」。
     * 実走103分では、yaw条件なしで129回・ありで2回まで落ちた。
     */
    @Test
    fun `左右を伴う首の動きは候補にならない`() {
        val detector = NoddingDetector(settings)
        val state = feedNods(
            detector, nods = 4, amplitudeDeg = 30f, eyeOpen = 0.9f,
            yaw = { t -> if ((t / 1000L) % 2L == 0L) 0f else 25f },
        )
        assertThat(state.yawRangeDeg).isAtLeast(settings.noddingCandidateMaxYawRangeDeg)
        assertThat(state.isOpenEyeCandidate).isFalse()
    }

    /** 実走の覚醒時の典型(振幅15°)は候補にならない。 */
    @Test
    fun `振幅が小さい上下動は候補にならない`() {
        val detector = NoddingDetector(settings)
        val state = feedNods(detector, nods = 4, amplitudeDeg = 15f, eyeOpen = 0.9f, yaw = { 0f })
        assertThat(state.reversalCount).isAtLeast(settings.noddingMinReversals)
        assertThat(state.isOpenEyeCandidate).isFalse()
    }

    /** yawが渡されていない(取れていない)窓では、左右を見ていたか言えないので候補にしない。 */
    @Test
    fun `yawが無ければ候補にしない`() {
        val detector = NoddingDetector(settings)
        val state = feedNods(detector, nods = 4, amplitudeDeg = 30f, eyeOpen = 0.9f, yaw = null)
        assertThat(state.yawRangeDeg).isNull()
        assertThat(state.isOpenEyeCandidate).isFalse()
    }
}
