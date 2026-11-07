package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import com.trypro.opendms.vision.FeatureFrame
import com.trypro.opendms.vision.QualityFlag
import com.trypro.opendms.vision.SignalQuality
import org.junit.Test

/**
 * 閉眼較正([EyeClosedCalibrationCollector])。
 *
 * 数値は2026-09-11の実測に合わせている: 開眼基準0.74、目を閉じたときの生値0.32
 * (正規化比0.43)。固定閾値0.45はこの真上に乗っていて、9.3秒閉じてもタイマーが進まなかった。
 */
class EyeClosedCalibrationCollectorTest {

    private val settings = DayMvpSettings.DAY_MVP0_DEFAULT
    private val open = CalibrationProfile(
        baselineYawDeg = 0f, baselinePitchDeg = 0f, baselineRollDeg = 0f,
        eyeOpenLeftBaseline = 0.74f, eyeOpenRightBaseline = 0.74f,
        faceSizeBaseline = 0.25f, calibratedAtUtcMs = 0L, settingsVersion = settings.version,
    )

    private fun frame(atMs: Long, eye: Float, valid: Boolean = true) = FeatureFrame(
        monotonicTimeMs = atMs, wallClockUtcMs = 0L,
        faceDetected = valid, confidence = null, faceSizeRatio = 0.25f,
        eyeLeftOpen = eye, eyeRightOpen = eye, jawOpen = 0f,
        headYawDeg = 0f, headPitchDeg = 0f, headRollDeg = 0f,
        luminance = 120f, inferenceTimeMs = 50L, fps = 20f, thermalStatus = 0,
        imageWidth = 480, imageHeight = 640, landmarks = emptyList(),
        quality = SignalQuality(
            score = if (valid) 1f else 0f,
            flags = if (valid) emptySet() else setOf(QualityFlag.NO_FACE),
            isValidForDecision = valid,
        ),
    )

    /** [fromMs]から[toMs]まで50ms刻み(20fps)で、生の開眼度[eye]のフレームを流す。 */
    private fun feed(c: EyeClosedCalibrationCollector, fromMs: Long, toMs: Long, eye: (Long) -> Float) {
        var t = fromMs
        while (t <= toMs) {
            c.addFrame(frame(t, eye(t)))
            t += 50L
        }
    }

    /** 本題。0.5秒で閉じ切り、あとは安定 → 3秒で取れる。閾値は閉0.43+0.3×0.57=0.60。 */
    @Test
    fun `閉じて安定すれば3秒で取れ_閾値は閉側3割の位置になる`() {
        val c = EyeClosedCalibrationCollector(settings, open)
        feed(c, 0L, 3_000L) { t -> if (t < 500L) 0.70f else 0.32f }
        assertThat(c.isSatisfied()).isTrue()
        val p = (c.finish() as CalibrationResult.Success).profile
        assertThat(p.hasEyeClosedCalibration).isTrue()
        assertThat(p.eyeClosedLeftBaseline).isWithin(0.01f).of(0.32f)
        // 0.32/0.74 = 0.432 → 0.432 + 0.30 × 0.568 = 0.603
        assertThat(p.eyeClosedOpenRatioThreshold).isWithin(0.02f).of(0.60f)
        // 開眼側の値は引き継ぐ。
        assertThat(p.eyeOpenLeftBaseline).isEqualTo(0.74f)
    }

    /**
     * 閉じるのが遅い人。2.5秒たってから閉じる。
     * 3秒時点では安定区間(直近1.5秒)に開いていた頃の値が混ざって取れないが、
     * 4秒まで待てば取れる。**時間で切らず質で切る**ので、延長が意味を持つ。
     */
    @Test
    fun `閉じるのが遅れても延長すれば取れる`() {
        val c = EyeClosedCalibrationCollector(settings, open)
        feed(c, 0L, 3_000L) { t -> if (t < 2_500L) 0.70f else 0.32f }
        assertThat(c.isSatisfied()).isFalse()
        feed(c, 3_050L, 4_000L) { 0.32f }
        assertThat(c.isSatisfied()).isTrue()
        val p = (c.finish() as CalibrationResult.Success).profile
        assertThat(p.eyeClosedOpenRatioThreshold).isWithin(0.02f).of(0.60f)
    }

    /**
     * **最も価値のある失敗。**目を閉じても開眼比が下がらない = 閉じていないかサングラス。
     * 2026-09-09のサングラス着用時は正規化比0.7〜0.8までしか下がらなかった。
     */
    @Test
    fun `開との差が小さければ目を閉じていないかサングラスとして失敗する`() {
        val c = EyeClosedCalibrationCollector(settings, open)
        feed(c, 0L, 6_000L) { 0.56f } // 0.56/0.74 = 0.76 → 差0.24 < 0.30
        assertThat(c.isSatisfied()).isFalse()
        val f = (c.finish() as CalibrationResult.Failure).failure
        assertThat(f.reason).isEqualTo(CalibrationFailureReason.EYES_NOT_CLOSED_OR_OCCLUDED)
        assertThat(f.observedClosedRatio).isWithin(0.02f).of(0.76f)
    }

    /** 瞬き・半開きを繰り返して値が暴れる → 不安定として失敗。 */
    @Test
    fun `値が安定しなければ失敗する`() {
        val c = EyeClosedCalibrationCollector(settings, open)
        // 0.15と0.55を交互に(正規化比 0.20 と 0.74)。中央値は低いが四分位範囲が大きい。
        feed(c, 0L, 6_000L) { t -> if ((t / 50L) % 2L == 0L) 0.15f else 0.55f }
        val f = (c.finish() as CalibrationResult.Failure).failure
        assertThat(f.reason).isEqualTo(CalibrationFailureReason.UNSTABLE)
    }

    /** 顔が取れていないフレームばかりなら、フレーム不足。 */
    @Test
    fun `有効フレームが足りなければ失敗する`() {
        val c = EyeClosedCalibrationCollector(settings, open)
        var t = 0L
        while (t <= 6_000L) { c.addFrame(frame(t, 0.32f, valid = false)); t += 50L }
        val f = (c.finish() as CalibrationResult.Failure).failure
        assertThat(f.reason).isEqualTo(CalibrationFailureReason.INSUFFICIENT_VALID_FRAMES)
        assertThat(f.excludedByFlag[QualityFlag.NO_FACE]).isGreaterThan(0)
    }

    /**
     * **2026-09-14の実車で起きた不具合の回帰。**サングラス越しで閉眼が0.69までしか下がらない。
     * 旧上限0.70で切ると閾値が閉眼値とほぼ同じになり、目を閉じても届かなかった。
     * 閾値は閉眼値より必ず上(+0.05以上)に置く。
     */
    @Test
    fun `閉眼値が高くても閾値は閉眼値より上に置かれる`() {
        val c = EyeClosedCalibrationCollector(settings, open)
        feed(c, 0L, 3_000L) { 0.51f } // 0.51/0.74 = 0.689 → 差0.311(ぎりぎり通る)
        val p = (c.finish() as CalibrationResult.Success).profile
        val closed = p.eyeClosedNormalizedRatio!!
        assertThat(closed).isWithin(0.01f).of(0.689f)
        // 0.689 + 0.30×0.311 = 0.78。旧上限0.70なら閉眼値を下回っていた。
        assertThat(p.eyeClosedOpenRatioThreshold).isWithin(0.02f).of(0.78f)
        assertThat(p.eyeClosedOpenRatioThreshold).isAtLeast(closed + settings.eyeClosedCalibrationMinMarginAboveClosed - 0.001f)
        // 差0.31は「不安定」の領域(0.40未満)。呼び出し側が警告を出すための材料。
        assertThat(1f - closed).isLessThan(settings.eyeClosedCalibrationMarginalGap)
    }

    /** 閾値は保険の範囲に収める。極端に閉じる人(閉0.05)でも0.35を下回らない。 */
    @Test
    fun `算出した閾値は下限を下回らない`() {
        val c = EyeClosedCalibrationCollector(settings, open)
        feed(c, 0L, 3_000L) { 0.04f } // 正規化0.054 → 0.054+0.3×0.946=0.34 → 下限0.35へ
        val p = (c.finish() as CalibrationResult.Success).profile
        assertThat(p.eyeClosedOpenRatioThreshold).isEqualTo(settings.eyeClosedCalibrationThresholdMin)
    }

    /** 公開前レビュー R-04: カメラが止まり同じフレームが繰り返し届いても、取れたことにしない。 */
    @Test
    fun `同じフレームを何度渡しても安定区間の枚数に数えない`() {
        val c = EyeClosedCalibrationCollector(settings, open)
        val f = frame(1_000L, 0.32f)
        repeat(100) { c.addFrame(f) }
        assertThat(c.validFrameCount).isEqualTo(1)
        assertThat(c.isSatisfied()).isFalse()
        val failure = (c.finish() as CalibrationResult.Failure).failure
        assertThat(failure.reason).isEqualTo(CalibrationFailureReason.INSUFFICIENT_VALID_FRAMES)
    }
}
