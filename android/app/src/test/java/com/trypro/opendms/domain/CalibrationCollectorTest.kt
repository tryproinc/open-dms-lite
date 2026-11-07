package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import com.trypro.opendms.vision.FeatureFrame
import com.trypro.opendms.vision.QualityFlag
import com.trypro.opendms.vision.SignalQuality
import org.junit.Test

/**
 * D0-006 Gate: 「有効frame不足時に理由表示、数値のみ保存」を検証するL0テスト。
 * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.3: 「較正は連続10秒のうち最低60有効frameを必要とする」。
 */
class CalibrationCollectorTest {

    private val settings = DayMvpSettings.DAY_MVP0_DEFAULT // calibrationMinValidFrames = 60

    private var lastAtMs = 0L

    /** 呼ぶたびに時刻を進める(同じフレームは数えないため。R-04)。 */
    private fun nextAtMs(): Long {
        lastAtMs += 66L
        return lastAtMs
    }

    private fun validFrame(
        atMs: Long = nextAtMs(),
        yaw: Float = 0f,
        pitch: Float = 0f,
        roll: Float = 0f,
        eyeLeft: Float = 0.9f,
        eyeRight: Float = 0.9f,
        faceSize: Float = 0.35f,
    ) = FeatureFrame(
        monotonicTimeMs = atMs,
        wallClockUtcMs = 0L,
        faceDetected = true,
        confidence = null,
        faceSizeRatio = faceSize,
        eyeLeftOpen = eyeLeft,
        eyeRightOpen = eyeRight,
        jawOpen = 0f,
        headYawDeg = yaw,
        headPitchDeg = pitch,
        headRollDeg = roll,
        luminance = 120f,
        inferenceTimeMs = 50L,
        fps = 15f,
        thermalStatus = 0,
        imageWidth = 480,
        imageHeight = 640,
        landmarks = emptyList(),
        quality = SignalQuality(score = 1f, flags = emptySet(), isValidForDecision = true),
    )

    private fun invalidFrame(flags: Set<QualityFlag>) =
        validFrame().copy(quality = SignalQuality(score = 0f, flags = flags, isValidForDecision = false))

    @Test
    fun `60有効frameちょうどで較正成功し中央値が正しく計算される`() {
        val collector = CalibrationCollector(settings)
        // yaw 1..60の中央値は30.5になるはず。
        (1..60).forEach { i -> collector.addFrame(validFrame(yaw = i.toFloat())) }

        val result = collector.finish(nowUtcMs = 1_000L)

        assertThat(result).isInstanceOf(CalibrationResult.Success::class.java)
        val profile = (result as CalibrationResult.Success).profile
        assertThat(profile.baselineYawDeg).isEqualTo(30.5f)
        assertThat(profile.settingsVersion).isEqualTo(settings.version)
        assertThat(profile.calibratedAtUtcMs).isEqualTo(1_000L)
    }

    @Test
    fun `59有効frameでは不足として失敗し理由が分かる`() {
        val collector = CalibrationCollector(settings)
        repeat(59) { collector.addFrame(validFrame()) }

        val result = collector.finish(nowUtcMs = 1_000L)

        assertThat(result).isInstanceOf(CalibrationResult.Failure::class.java)
        val failure = (result as CalibrationResult.Failure).failure
        assertThat(failure.reason).isEqualTo(CalibrationFailureReason.INSUFFICIENT_VALID_FRAMES)
        assertThat(failure.validFrameCount).isEqualTo(59)
        assertThat(failure.requiredFrameCount).isEqualTo(60)
    }

    @Test
    fun `品質無効なframeは較正サンプルから除外され理由の内訳に計上される`() {
        val collector = CalibrationCollector(settings)
        repeat(50) { collector.addFrame(validFrame()) }
        repeat(5) { collector.addFrame(invalidFrame(setOf(QualityFlag.NO_FACE))) }
        repeat(3) { collector.addFrame(invalidFrame(setOf(QualityFlag.LOW_LIGHT))) }

        val result = collector.finish(nowUtcMs = 1_000L)

        assertThat(result).isInstanceOf(CalibrationResult.Failure::class.java)
        val failure = (result as CalibrationResult.Failure).failure
        assertThat(failure.validFrameCount).isEqualTo(50)
        assertThat(failure.totalFrameCount).isEqualTo(58)
        assertThat(failure.excludedByFlag[QualityFlag.NO_FACE]).isEqualTo(5)
        assertThat(failure.excludedByFlag[QualityFlag.LOW_LIGHT]).isEqualTo(3)
    }

    @Test
    fun `品質は有効だが値が欠けているframeは較正サンプルにも除外理由にも含めない`() {
        val collector = CalibrationCollector(settings)
        repeat(60) { collector.addFrame(validFrame()) }
        // qualityはvalidだがheadYawDeg等がnull(通常起きない組み合わせだが防御的に確認する)。
        collector.addFrame(validFrame().copy(headYawDeg = null))

        assertThat(collector.validFrameCount).isEqualTo(60)
        assertThat(collector.currentTotalFrameCount).isEqualTo(61)

        val result = collector.finish(nowUtcMs = 1_000L)
        assertThat(result).isInstanceOf(CalibrationResult.Success::class.java)
    }

    /** 公開前レビュー R-04: 古い1フレームを100回渡すと較正が通っていた(カメラが止まった状態)。 */
    @Test
    fun `同じフレームを何度渡しても1枚と数え較正は失敗する`() {
        val collector = CalibrationCollector(settings)
        val frame = validFrame()
        repeat(100) { collector.addFrame(frame) }

        assertThat(collector.validFrameCount).isEqualTo(1)
        assertThat(collector.finish(nowUtcMs = 0L)).isInstanceOf(CalibrationResult.Failure::class.java)
    }

    @Test
    fun `時刻が戻ったフレームは数えない`() {
        val collector = CalibrationCollector(settings)
        collector.addFrame(validFrame(atMs = 1_000L))
        collector.addFrame(validFrame(atMs = 900L))
        collector.addFrame(validFrame(atMs = 1_100L))

        assertThat(collector.validFrameCount).isEqualTo(2)
        assertThat(collector.currentTotalFrameCount).isEqualTo(2)
    }
}
