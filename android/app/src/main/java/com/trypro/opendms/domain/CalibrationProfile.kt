package com.trypro.opendms.domain

import com.trypro.opendms.vision.QualityFlag

/**
 * 較正結果の数値のみを保持する型。11_DAY_SMARTPHONE_MVP_PLAN.md 3.3:
 * 「較正値はDataStoreへ数値だけ保存し...」画像・landmark・生frameは一切含まない。
 */
data class CalibrationProfile(
    val baselineYawDeg: Float,
    val baselinePitchDeg: Float,
    val baselineRollDeg: Float,
    /** 開眼時の左目開度中央値。判定時はこれで正規化して1.0を「全開」とする(D0-007で使用)。 */
    val eyeOpenLeftBaseline: Float,
    val eyeOpenRightBaseline: Float,
    val faceSizeBaseline: Float,
    val calibratedAtUtcMs: Long,
    val settingsVersion: String,
    /**
     * 閉眼時の左目開度中央値(生値)。閉眼較正をしていなければnull。
     * 2026-09-11追加([EyeClosedCalibrationCollector])。
     */
    val eyeClosedLeftBaseline: Float? = null,
    val eyeClosedRightBaseline: Float? = null,
    /**
     * この人・この端末で「閉眼」とみなす**正規化開眼比の上限**。
     * 閉眼較正から算出する。nullなら[DayMvpSettings.eyeClosedOpenRatioThreshold]を使う。
     *
     * 目を閉じたときの値は人・カメラ・距離で変わる(2026-09-11の実測で0.43)。
     * 固定の閾値ではその真上に乗ることがあり、閉じている間じゅう出入りしてタイマーが進まなかった。
     */
    val eyeClosedOpenRatioThreshold: Float? = null,
) {
    /** 閉眼較正まで済んでいるか。 */
    val hasEyeClosedCalibration: Boolean get() = eyeClosedOpenRatioThreshold != null

    /**
     * 閉眼時の正規化開眼比(左右平均)。閉眼較正が無ければnull。
     * 1.0からの差が小さいほど、閉眼と「開いて下を見た」の区別がつきにくい。
     */
    val eyeClosedNormalizedRatio: Float?
        get() {
            val l = eyeClosedLeftBaseline ?: return null
            val r = eyeClosedRightBaseline ?: return null
            if (eyeOpenLeftBaseline <= 0f || eyeOpenRightBaseline <= 0f) return null
            return (l / eyeOpenLeftBaseline + r / eyeOpenRightBaseline) / 2f
        }
}

enum class CalibrationFailureReason {
    INSUFFICIENT_VALID_FRAMES,

    /**
     * 閉眼較正で、開と閉の差が小さすぎた。「目を閉じていない」か
     * 「サングラス等で瞼が読めない」。どちらでも閉眼判定は成立しない。
     */
    EYES_NOT_CLOSED_OR_OCCLUDED,

    /** 閉眼較正で、値が最後まで安定しなかった(瞬き・半開きを繰り返した)。 */
    UNSTABLE,
}

/** 較正失敗時に理由を画面へ表示するための情報(3.3: 「60有効frame未満なら理由を表示してやり直す」)。 */
data class CalibrationFailure(
    val reason: CalibrationFailureReason,
    val validFrameCount: Int,
    val requiredFrameCount: Int,
    val totalFrameCount: Int,
    /** 除外理由の内訳。1frameが複数flagを同時に持つ場合は両方へ計上する。 */
    val excludedByFlag: Map<QualityFlag, Int>,
    /** 閉眼較正のとき、最後に見えていた正規化開眼比(閉)。理由の説明に使う。無ければnull。 */
    val observedClosedRatio: Float? = null,
)

sealed interface CalibrationResult {
    data class Success(val profile: CalibrationProfile) : CalibrationResult
    data class Failure(val failure: CalibrationFailure) : CalibrationResult
}
