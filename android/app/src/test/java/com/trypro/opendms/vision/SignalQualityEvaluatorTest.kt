package com.trypro.opendms.vision

import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import com.trypro.opendms.domain.DayMvpSettings
import org.junit.Test

/**
 * D0-005 Gate: 「顔なし、顔小、片目、暗さ、低fpsを区別」できることを確認するL0テスト。
 * D0-006でDayMvpSettings/相対頭部角度(較正基準)対応に更新した。
 * 閾値自体は暫定値(DayMvpSettings参照)であり、ここでは「区別できる」ことを検証する。
 */
class SignalQualityEvaluatorTest {

    private val settings = DayMvpSettings.DAY_MVP0_DEFAULT

    private fun evaluate(
        faceDetected: Boolean = true,
        faceSizeRatio: Float? = 0.35f,
        headYawDeg: Float? = 0f,
        headPitchDeg: Float? = 0f,
        calibrationBaselineYawDeg: Float? = null,
        calibrationBaselinePitchDeg: Float? = null,
        luminance: Float? = 120f,
        fps: Float = 15f,
        thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
        headAngularVelocityDegPerSec: Float? = null,
    ) = SignalQualityEvaluator.evaluate(
        settings = settings,
        faceDetected = faceDetected,
        faceSizeRatio = faceSizeRatio,
        headYawDeg = headYawDeg,
        headPitchDeg = headPitchDeg,
        calibrationBaselineYawDeg = calibrationBaselineYawDeg,
        calibrationBaselinePitchDeg = calibrationBaselinePitchDeg,
        luminance = luminance,
        fps = fps,
        thermalStatus = thermalStatus,
        headAngularVelocityDegPerSec = headAngularVelocityDegPerSec,
    )

    @Test
    fun `良好な条件ではNO_FACEを含む全フラグが立たずvalidになる`() {
        val quality = evaluate()
        assertThat(quality.flags).isEmpty()
        assertThat(quality.isValidForDecision).isTrue()
        assertThat(quality.score).isEqualTo(1f)
    }

    @Test
    fun `顔が検出されないとNO_FACEのみが立ちscoreは0`() {
        val quality = evaluate(faceDetected = false, faceSizeRatio = null, headYawDeg = null, headPitchDeg = null, luminance = null)
        assertThat(quality.flags).containsExactly(QualityFlag.NO_FACE)
        assertThat(quality.score).isEqualTo(0f)
        assertThat(quality.isValidForDecision).isFalse()
    }

    @Test
    fun `顔サイズが小さいとFACE_TOO_SMALLが立つ`() {
        val quality = evaluate(faceSizeRatio = 0.10f)
        assertThat(quality.flags).containsExactly(QualityFlag.FACE_TOO_SMALL)
        assertThat(quality.isValidForDecision).isFalse()
    }

    @Test
    fun `頭部が大きく横を向くとONE_EYE_ONLYが立つ`() {
        val quality = evaluate(headYawDeg = 32f)
        assertThat(quality.flags).containsExactly(QualityFlag.ONE_EYE_ONLY)
    }

    @Test
    fun `頭部角度がさらに大きいとHEAD_ANGLE_TOO_LARGEも立つ`() {
        val quality = evaluate(headYawDeg = 70f)
        assertThat(quality.flags).containsAtLeast(QualityFlag.ONE_EYE_ONLY, QualityFlag.HEAD_ANGLE_TOO_LARGE)
    }

    @Test
    fun `較正済み基準がある場合は絶対角度でなく相対角度で判定する`() {
        // 基準yawが40度でも、現在値が40度付近(相対0度)ならONE_EYE_ONLYは立たない。
        val quality = evaluate(headYawDeg = 42f, calibrationBaselineYawDeg = 40f)
        assertThat(quality.flags).doesNotContain(QualityFlag.ONE_EYE_ONLY)
    }

    @Test
    fun `輝度が低いとLOW_LIGHTが立つ`() {
        val quality = evaluate(luminance = 10f)
        assertThat(quality.flags).containsExactly(QualityFlag.LOW_LIGHT)
    }

    @Test
    fun `fpsが低いとLOW_FPSが立つ`() {
        val quality = evaluate(fps = 3f)
        assertThat(quality.flags).containsExactly(QualityFlag.LOW_FPS)
    }

    @Test
    fun `起動直後のfps0はLOW_FPSと誤認しない`() {
        val quality = evaluate(fps = 0f)
        assertThat(quality.flags).doesNotContain(QualityFlag.LOW_FPS)
    }

    @Test
    fun `thermalがMODERATE以上だとTHERMAL_DEGRADEDが立つ`() {
        val quality = evaluate(thermalStatus = PowerManager.THERMAL_STATUS_MODERATE)
        assertThat(quality.flags).containsExactly(QualityFlag.THERMAL_DEGRADED)
    }

    /**
     * 2026-10-17: Nothing Phone (2a)は室温でもMODERATE〜SEVEREを返し、常に減点されていた。
     * 高温は記録だけにし、判定には使わない(処理の遅れはLOW_FPSが見る)。
     */
    @Test
    fun `THERMAL_DEGRADEDは減点せず低fpsと重なっても判定に使える`() {
        val hot = evaluate(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE)
        val hotAndSlow = evaluate(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE, fps = 5f)

        assertThat(hot.score).isEqualTo(1f)
        assertThat(hotAndSlow.isValidForDecision).isTrue()
    }

    // 2026-08-29実機検証: 閉眼中の軽い首の動き(合成角速度20〜40°/s程度)でeyeBlink blendshapeが
    // 一時的に開眼寄りへ振れ、閉眼継続タイマーが途切れる現象を確認。品質無効として除外する。
    /**
     * 2026-08-29回帰: 頭部回転中は開眼度(blendshape)のみが信頼できず、頭部角度は有効。
     * したがってHEAD_ROTATING_RAPIDLYは`isFacialSignalReliable`だけをfalseにし、
     * `isValidForDecision`は維持する(維持しないと脇見判定が首振り中に成立しなくなる)。
     */
    @Test
    fun `頭部の合成角速度が閾値超だと開眼度のみ不信頼になり判定自体は有効のまま`() {
        val quality = evaluate(headAngularVelocityDegPerSec = 60f)
        assertThat(quality.flags).containsExactly(QualityFlag.HEAD_ROTATING_RAPIDLY)
        assertThat(quality.isFacialSignalReliable).isFalse()
        assertThat(quality.isValidForDecision).isTrue()
    }

    @Test
    fun `頭部の合成角速度が閾値以下ならHEAD_ROTATING_RAPIDLYは立たない`() {
        val quality = evaluate(headAngularVelocityDegPerSec = 20f)
        assertThat(quality.flags).doesNotContain(QualityFlag.HEAD_ROTATING_RAPIDLY)
        assertThat(quality.isFacialSignalReliable).isTrue()
    }

    /**
     * 2026-08-29回帰: 自然な「下を向く」動作の実測は相対pitch -45°〜+50°。
     * 旧上限35°では脇見判定帯(20°〜)を通過直後に品質無効となり脇見が成立しなかった。
     */
    @Test
    fun `相対pitch60度は品質有効のまま(自然な下向き動作を脇見判定から除外しない)`() {
        val quality = evaluate(headPitchDeg = 60f)
        assertThat(quality.flags).doesNotContain(QualityFlag.HEAD_ANGLE_TOO_LARGE)
        assertThat(quality.isValidForDecision).isTrue()
    }

    @Test
    fun `相対pitchが上限70度を超えるとHEAD_ANGLE_TOO_LARGEが立つ`() {
        val quality = evaluate(headPitchDeg = 75f)
        assertThat(quality.flags).contains(QualityFlag.HEAD_ANGLE_TOO_LARGE)
        // 2026-09-01: このフラグは**判定を無効化しない**ように変えた。
        // 顔の表情特徴は読めないが、「前方を見ていない」ことは確実に言えるため。
        assertThat(quality.isValidForDecision).isTrue()
        assertThat(quality.isFacialSignalReliable).isFalse()
    }

    /**
     * 2026-09-01回帰(実機session_b4ce5f53): **脇見が原理的に成立しなかった不具合。**
     *
     * 脇見の閾値は相対|yaw| >= 25°だが、`ONE_EYE_ONLY`(> 30°)の減点0.35で
     * score 0.65 < 閾値0.70となり判定無効になっていた。
     * 「判定可のまま脇見と言える」帯は25〜30°の5°しかなく、
     * 実測634フレーム中その帯に入ったのは1フレームだけだった。
     */
    @Test
    fun `横を向いた角度でも判定は有効なまま(脇見を成立させるため)`() {
        listOf(32f, 45f, 70f).forEach { yaw ->
            val quality = evaluate(headYawDeg = yaw)
            assertThat(quality.isValidForDecision).isTrue()
            // 表情特徴だけが信用できない、という扱いに揃える。
            assertThat(quality.isFacialSignalReliable).isFalse()
        }
    }

    /** 顔が取れていないフレームは従来どおり判定から外す(角度も読めていないため)。 */
    @Test
    fun `顔が取れていなければ横を向いていても判定は無効`() {
        val quality = evaluate(faceDetected = false, headYawDeg = 45f)
        assertThat(quality.isValidForDecision).isFalse()
    }

    @Test
    fun `頭部角速度がnull(計算不能)ならHEAD_ROTATING_RAPIDLYは立たない`() {
        val quality = evaluate(headAngularVelocityDegPerSec = null)
        assertThat(quality.flags).doesNotContain(QualityFlag.HEAD_ROTATING_RAPIDLY)
    }

    /**
     * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.4「顔幅がframe幅の20〜60%目安」の上限側。
     * D0-006では下限のみ実装され上限が未配線だった。
     */
    @Test
    fun `顔サイズが上限を超えるとFACE_TOO_LARGEが立つ`() {
        val quality = evaluate(faceSizeRatio = 0.75f)
        assertThat(quality.flags).containsExactly(QualityFlag.FACE_TOO_LARGE)
        assertThat(quality.isValidForDecision).isFalse()
    }

    @Test
    fun `顔サイズが範囲内ならサイズ系フラグは立たない`() {
        val quality = evaluate(faceSizeRatio = 0.40f)
        assertThat(quality.flags).doesNotContain(QualityFlag.FACE_TOO_LARGE)
        assertThat(quality.flags).doesNotContain(QualityFlag.FACE_TOO_SMALL)
    }

    @Test
    fun `複数条件が同時に悪化すると全て区別して立つ`() {
        val quality = evaluate(
            faceSizeRatio = 0.10f,
            headYawDeg = 70f,
            luminance = 10f,
            fps = 3f,
            thermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
        )
        assertThat(quality.flags).containsExactly(
            QualityFlag.FACE_TOO_SMALL,
            QualityFlag.ONE_EYE_ONLY,
            QualityFlag.HEAD_ANGLE_TOO_LARGE,
            QualityFlag.LOW_LIGHT,
            QualityFlag.LOW_FPS,
            QualityFlag.THERMAL_DEGRADED,
        )
        assertThat(quality.isValidForDecision).isFalse()
    }
}
