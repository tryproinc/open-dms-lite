package com.trypro.opendms.domain

import com.trypro.opendms.vision.FeatureFrame
import com.trypro.opendms.vision.QualityFlag

/**
 * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.3の較正ロジック。Android非依存の純粋Kotlinでテストできるようにする。
 *
 * 呼び出し側は約10秒間、到着した[FeatureFrame]を都度[addFrame]し、期間終了後に[finish]を呼ぶ。
 * 「顔消失、片目欠落、低照度、大きな頭部移動、10fps未満の区間」(=品質無効なフレーム)は
 * 較正サンプルから除外する。60有効frame未満なら[CalibrationResult.Failure]を返す。
 *
 * **同じフレームは2回数えない**(公開前レビュー R-04)。呼び出し側は直近のフレームを100msごとに
 * 渡すだけなので、カメラが止まると同じフレームが届き続ける。時刻が前より新しいものだけを使う。
 */
class CalibrationCollector(private val settings: DayMvpSettings) {
    private val validYaw = mutableListOf<Float>()
    private val validPitch = mutableListOf<Float>()
    private val validRoll = mutableListOf<Float>()
    private val validEyeLeft = mutableListOf<Float>()
    private val validEyeRight = mutableListOf<Float>()
    private val validFaceSize = mutableListOf<Float>()
    private val excludedByFlag = mutableMapOf<QualityFlag, Int>()
    private var totalFrameCount = 0
    private var lastFrameMs = Long.MIN_VALUE

    val validFrameCount: Int get() = validYaw.size
    val currentTotalFrameCount: Int get() = totalFrameCount

    fun addFrame(frame: FeatureFrame) {
        if (frame.monotonicTimeMs <= lastFrameMs) return
        lastFrameMs = frame.monotonicTimeMs
        totalFrameCount += 1

        if (!frame.quality.isValidForDecision) {
            frame.quality.flags.forEach { flag ->
                excludedByFlag[flag] = (excludedByFlag[flag] ?: 0) + 1
            }
            return
        }

        val yaw = frame.headYawDeg
        val pitch = frame.headPitchDeg
        val roll = frame.headRollDeg
        val eyeLeft = frame.eyeLeftOpen
        val eyeRight = frame.eyeRightOpen
        val faceSize = frame.faceSizeRatio
        if (yaw == null || pitch == null || roll == null || eyeLeft == null || eyeRight == null || faceSize == null) {
            // 品質は有効でも値が欠けているフレームは較正の中央値計算に使えないため単純に無視する
            // (excludedByFlagには計上しない。SignalQualityのflagではなく値の欠落が原因のため)。
            return
        }

        validYaw += yaw
        validPitch += pitch
        validRoll += roll
        validEyeLeft += eyeLeft
        validEyeRight += eyeRight
        validFaceSize += faceSize
    }

    fun finish(nowUtcMs: Long): CalibrationResult {
        val count = validYaw.size
        if (count < settings.calibrationMinValidFrames) {
            return CalibrationResult.Failure(
                CalibrationFailure(
                    reason = CalibrationFailureReason.INSUFFICIENT_VALID_FRAMES,
                    validFrameCount = count,
                    requiredFrameCount = settings.calibrationMinValidFrames,
                    totalFrameCount = totalFrameCount,
                    excludedByFlag = excludedByFlag.toMap(),
                ),
            )
        }

        return CalibrationResult.Success(
            CalibrationProfile(
                baselineYawDeg = validYaw.median(),
                baselinePitchDeg = validPitch.median(),
                baselineRollDeg = validRoll.median(),
                eyeOpenLeftBaseline = validEyeLeft.median(),
                eyeOpenRightBaseline = validEyeRight.median(),
                faceSizeBaseline = validFaceSize.median(),
                calibratedAtUtcMs = nowUtcMs,
                settingsVersion = settings.version,
            ),
        )
    }

}

private fun List<Float>.median(): Float {
    val sorted = sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
}
