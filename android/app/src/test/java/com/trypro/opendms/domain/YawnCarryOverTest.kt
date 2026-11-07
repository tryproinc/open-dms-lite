package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 運行をまたいだあくびの持ち越し(docs/18 §14-14)。
 *
 * 状態機械は運行開始のたびに作り直す(cooldownや継続時間を引きずらないため)。
 * そのため休憩で「運行終了→運行開始」を挟むと、それまでのあくびが消えて
 * 頻発(既定10分3回)が成立しなくなっていた。**眠気は運行の区切りとは無関係に続く**ので、
 * あくびの窓だけは引き継ぐ。
 */
class YawnCarryOverTest {

    private val settings = DayMvpSettings.DAY_MVP0_DEFAULT

    /** あくび1回に足りる、100ms刻みの開口の回数(連続時間の下限より0.4秒長く)。 */
    private val yawnSteps = (settings.yawnMinDurationMs / 100 + 4).toInt()

    /** あくび1回を成立させる。閾値0.45以上・連続時間の下限以上・ピーク0.60以上。 */
    private fun feedOneYawn(detector: YawnDetector, startMs: Long): Long {
        var t = startMs
        // 100ms刻みで下限より0.4秒長く、深さ0.8。連続時間とピークの両方を満たす。
        repeat(yawnSteps) {
            detector.update(t, 0.80f)
            t += 100
        }
        // 口を閉じてepisodeを終わらせる(次のあくびを別episodeとして数えられるように)。
        detector.update(t, 0.0f)
        return t + 100
    }

    @Test
    fun `あくび2回のあと運行を切り替えても回数が引き継がれる`() {
        val first = YawnDetector(settings)
        var t = 10_000L
        t = feedOneYawn(first, t)
        t = feedOneYawn(first, t)
        assertThat(first.exportYawnTimes()).hasSize(2)

        // 運行終了 → 運行開始。状態機械もYawnDetectorも作り直される。
        val second = YawnDetector(settings)
        second.restoreYawnTimes(first.exportYawnTimes())

        // 新しい運行で3回目。**引き継ぎが効いていれば、ここで頻発が成立する。**
        t = feedOneYawn(second, t)
        val state = second.update(t, 0.0f)
        assertThat(state.countInWindow).isEqualTo(3)
        assertThat(state.isFrequent).isTrue()
    }

    @Test
    fun `引き継がないと3回目でも頻発しない`() {
        val first = YawnDetector(settings)
        var t = 10_000L
        t = feedOneYawn(first, t)
        t = feedOneYawn(first, t)

        // 引き継ぎなし(従来の挙動)。
        val second = YawnDetector(settings)
        t = feedOneYawn(second, t)
        val state = second.update(t, 0.0f)
        assertThat(state.countInWindow).isEqualTo(1)
        assertThat(state.isFrequent).isFalse()
    }

    /**
     * 窓(既定10分)より古い引き継ぎは捨てられること。
     * 長い休憩を挟んだあとに古いあくびで発報されるのは意図と違う。
     */
    @Test
    fun `窓より古い引き継ぎは自動的に捨てられる`() {
        val old = listOf(1_000L, 2_000L, 3_000L)
        val detector = YawnDetector(settings)
        detector.restoreYawnTimes(old)

        // 窓(10分=600,000ms)を大きく超えた時刻で評価する。
        val state = detector.update(1_000L + settings.yawnWindowMs + 60_000L, null)
        assertThat(state.countInWindow).isEqualTo(0)
        assertThat(state.isFrequent).isFalse()
    }

    @Test
    fun `進行中の開口は引き継がない`() {
        val first = YawnDetector(settings)
        // 口を開けたまま(閉じずに)運行を切り替える。
        var t = 10_000L
        repeat(5) {
            first.update(t, 0.80f)
            t += 100
        }
        // まだ1.0秒に達していないので成立はしていない。
        assertThat(first.exportYawnTimes()).isEmpty()

        val second = YawnDetector(settings)
        second.restoreYawnTimes(first.exportYawnTimes())
        // 新しい運行では、この開口は数え直しになる(0からのカウント)。
        val state = second.update(t, 0.80f)
        assertThat(state.countInWindow).isEqualTo(0)
        assertThat(state.currentOpenDurationMs).isEqualTo(0L)
    }

    @Test
    fun `状態機械ごしにも引き継げる`() {
        val machineA = DayDmsStateMachine(settings)
        var t = 10_000L
        // 状態機械経由であくびを2回。品質有効・表情信頼ありで渡す。
        repeat(2) {
            repeat(yawnSteps) {
                machineA.process(input(t, jawOpen = 0.80f))
                t += 100
            }
            machineA.process(input(t, jawOpen = 0f))
            t += 100
        }
        val carried = machineA.exportYawnHistory()
        assertThat(carried).hasSize(2)

        val machineB = DayDmsStateMachine(settings)
        machineB.restoreYawnHistory(carried)
        repeat(yawnSteps) {
            machineB.process(input(t, jawOpen = 0.80f))
            t += 100
        }
        val out = machineB.process(input(t, jawOpen = 0.80f))
        assertThat(out.yawn.countInWindow).isEqualTo(3)
        assertThat(out.yawn.isFrequent).isTrue()
    }

    private fun input(monotonicMs: Long, jawOpen: Float) = DmsInput(
        monotonicMs = monotonicMs,
        validForDecision = true,
        eyesClosed = false,
        distracted = false,
        facialSignalReliable = true,
        jawOpen = jawOpen,
    )
}
