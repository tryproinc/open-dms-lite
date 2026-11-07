package com.trypro.opendms.vision

import com.google.common.truth.Truth.assertThat
import com.trypro.opendms.domain.DayMvpSettings
import org.junit.Test

/**
 * [FaultMonitor]の単体試験。ASV眠気4章/脇見4章の「故障をドライバーが認識できる手段」の
 * 前提になる検知条件を、Androidに依存せず検証する。
 */
class FaultMonitorTest {

    private val settings = DayMvpSettings.DAY_MVP0_DEFAULT
    private val timeout = settings.faultNoResultTimeoutMs

    /** 正常に動いている状態を作る。バインドから猶予時間ぶん経過し、フレームも結果も流れている。 */
    private fun healthy(monitor: FaultMonitor, atMs: Long) {
        monitor.onCameraBound(0L)
        var t = 0L
        while (t <= atMs) {
            monitor.onFrameSubmitted(t)
            monitor.onResult(t)
            t += 100L
        }
    }

    @Test
    fun `正常に動いている間は故障としない`() {
        val monitor = FaultMonitor(settings)
        healthy(monitor, timeout * 3)

        assertThat(monitor.evaluate(timeout * 3)).isNull()
    }

    @Test
    fun `カメラのバインド失敗は即座に故障とする`() {
        val monitor = FaultMonitor(settings)
        monitor.onCameraBindFailed()

        assertThat(monitor.evaluate(0L)).isEqualTo(FaultReason.CAMERA_UNAVAILABLE)
    }

    /**
     * 2026-08-31実機確認の回帰: 起動直後に`NO_FRAME`を誤検知し、毎回の起動で故障アラームが鳴っていた。
     * `bindToLifecycle`が成功してもカメラの実起動とモデルのロードに数秒かかるため、
     * 定常時の閾値(3秒)ではなく起動用の長い猶予(既定10秒)を使う。
     */
    @Test
    fun `起動直後にフレームが来なくても起動猶予の間は故障としない`() {
        val monitor = FaultMonitor(settings)
        monitor.onCameraBound(0L)

        // 定常時の閾値は超えているが、まだ1枚も届いていない=起動中なので故障にしない。
        assertThat(monitor.evaluate(timeout + 1)).isNull()
        assertThat(monitor.evaluate(settings.faultStartupTimeoutMs - 1)).isNull()
    }

    @Test
    fun `起動猶予を過ぎてもフレームが来なければ故障とする`() {
        val monitor = FaultMonitor(settings)
        monitor.onCameraBound(0L)

        assertThat(monitor.evaluate(settings.faultStartupTimeoutMs)).isEqualTo(FaultReason.NO_FRAME)
    }

    @Test
    fun `起動猶予を過ぎてもフレームだけで結果が来なければ故障とする`() {
        val monitor = FaultMonitor(settings)
        monitor.onCameraBound(0L)
        var t = 0L
        while (t <= settings.faultStartupTimeoutMs) {
            monitor.onFrameSubmitted(t)
            t += 100L
        }

        assertThat(monitor.evaluate(settings.faultStartupTimeoutMs)).isEqualTo(FaultReason.NO_RESULT)
    }

    /** 一度フレームが流れ始めたら、以後は定常時の短い閾値へ切り替わる。 */
    @Test
    fun `フレームが流れ始めた後は定常時の短い閾値を使う`() {
        val monitor = FaultMonitor(settings)
        healthy(monitor, 500L)

        // 起動猶予(10秒)には遠く及ばないが、定常時の閾値(3秒)は超えているので故障とする。
        assertThat(monitor.evaluate(500L + timeout)).isEqualTo(FaultReason.NO_FRAME)
    }

    @Test
    fun `推論エラーが規定回数連続したら故障とする`() {
        val monitor = FaultMonitor(settings)
        healthy(monitor, timeout * 2)

        repeat(settings.faultConsecutiveInferenceErrors) { monitor.onInferenceError() }

        assertThat(monitor.evaluate(timeout * 2)).isEqualTo(FaultReason.INFERENCE_FAILING)
    }

    @Test
    fun `推論エラーが規定回数に1回足りなければ故障としない`() {
        val monitor = FaultMonitor(settings)
        healthy(monitor, timeout * 2)

        repeat(settings.faultConsecutiveInferenceErrors - 1) { monitor.onInferenceError() }

        assertThat(monitor.evaluate(timeout * 2)).isNull()
    }

    @Test
    fun `単発の推論エラーは結果が1つ返れば解消する`() {
        val monitor = FaultMonitor(settings)
        healthy(monitor, timeout * 2)

        // errorListenerは単発フレームの失敗でも呼ばれる。結果が返ればカウントは戻る。
        repeat(settings.faultConsecutiveInferenceErrors - 1) { monitor.onInferenceError() }
        monitor.onResult(timeout * 2)
        repeat(settings.faultConsecutiveInferenceErrors - 1) { monitor.onInferenceError() }

        assertThat(monitor.evaluate(timeout * 2)).isNull()
    }

    @Test
    fun `フレームは届くのに結果が返らなければ故障とする`() {
        val monitor = FaultMonitor(settings)
        healthy(monitor, timeout * 2)

        // 以後フレームだけ投入し続け、結果は返らない。
        val stallStart = timeout * 2
        var t = stallStart
        while (t <= stallStart + timeout) {
            monitor.onFrameSubmitted(t)
            t += 100L
        }

        assertThat(monitor.evaluate(stallStart + timeout)).isEqualTo(FaultReason.NO_RESULT)
    }

    @Test
    fun `フレームが届かなくなったら故障とする`() {
        val monitor = FaultMonitor(settings)
        healthy(monitor, timeout * 2)

        // フレームも結果も止まる。フレーム側の停止が先に判定されるのでNO_FRAME。
        assertThat(monitor.evaluate(timeout * 2 + timeout)).isEqualTo(FaultReason.NO_FRAME)
    }

    @Test
    fun `復旧したら故障ではなくなる`() {
        val monitor = FaultMonitor(settings)
        healthy(monitor, timeout * 2)
        assertThat(monitor.evaluate(timeout * 2 + timeout)).isEqualTo(FaultReason.NO_FRAME)

        val recoverAt = timeout * 2 + timeout
        monitor.onFrameSubmitted(recoverAt)
        monitor.onResult(recoverAt)

        assertThat(monitor.evaluate(recoverAt)).isNull()
    }

    /**
     * 回帰: `reset()`が起点をnullに戻すと、その後カメラが一度もバインドされない場合に
     * `evaluate`が永久にnullを返し、故障が報知されなかった。
     */
    @Test
    fun `リセット後にカメラが一度もバインドされなければ起動猶予の後に故障とする`() {
        val monitor = FaultMonitor(settings)
        healthy(monitor, timeout * 2)
        val resetAt = timeout * 2
        monitor.reset(resetAt)

        assertThat(monitor.evaluate(resetAt + settings.faultStartupTimeoutMs - 1)).isNull()
        assertThat(monitor.evaluate(resetAt + settings.faultStartupTimeoutMs)).isEqualTo(FaultReason.NO_FRAME)
    }

    @Test
    fun `バインドし直すと以前の失敗状態を引きずらない`() {
        val monitor = FaultMonitor(settings)
        monitor.onCameraBindFailed()
        assertThat(monitor.evaluate(0L)).isEqualTo(FaultReason.CAMERA_UNAVAILABLE)

        monitor.onCameraBound(1_000L)
        assertThat(monitor.evaluate(1_000L)).isNull()
    }
}
