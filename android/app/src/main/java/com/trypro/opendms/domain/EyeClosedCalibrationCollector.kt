package com.trypro.opendms.domain

import com.trypro.opendms.vision.FeatureFrame
import com.trypro.opendms.vision.QualityFlag

/**
 * 閉眼較正(2026-09-11)。「目を閉じてください」の間のフレームから、
 * **この人・このカメラで目を閉じたときの開眼度**を測り、閉眼判定の閾値を決める。
 *
 * ## なぜ要るのか
 *
 * 目を閉じたときの値は人・カメラ・距離で変わる。2026-09-11の実測では、
 * 目を完全に閉じても正規化開眼比が**0.40〜0.45**までしか下がらなかった
 * (MediaPipeのblink blendshapeは1.0まで振れない)。固定閾値0.45はその真上に乗っていて、
 * 閉じている間じゅう閾値を出入りし、そのたびに閉眼タイマーがリセットされていた
 * (9.3秒閉じてもタイマー最大368ms)。
 *
 * ## 進め方(呼び出し側)
 *
 * 1. 開眼較正([CalibrationCollector])を先に済ませ、その[CalibrationProfile]を渡す
 * 2. 合図を出して、届いた[FeatureFrame]を[addFrame]する
 * 3. [DayMvpSettings.eyeClosedCalibrationMinDurationMs](3秒)を過ぎたら、
 *    毎フレーム[isSatisfied]を見る。trueになったらそこで止めてよい
 * 4. [DayMvpSettings.eyeClosedCalibrationMaxDurationMs](6秒)まで待っても満たなければ止める
 * 5. [finish]で結果を受け取る
 *
 * 「3秒で足りなければ6秒まで自動で延ばす」のは、言われてから閉じるまでの反応が
 * 人によって違うため。**時間ではなく安定区間の質で採否を決める。**
 *
 * ## 「安定区間」
 *
 * 直近[DayMvpSettings.eyeClosedCalibrationStableWindowMs](1.5秒)の有効フレームだけを見る。
 * 閉じるのが遅れた人でも、閉じ切ったあとの区間だけが評価されるので、
 * 開いていた頃の値が混ざらない。
 *
 * その区間について、
 * - フレーム数が足りる
 * - ばらつき(四分位範囲)が小さい ── 瞬き・半開きを繰り返していない
 * - 開との差が十分ある ── **目を閉じていない、またはサングラス**を弾く
 *
 * の3つを満たしたとき「取れた」とする。
 *
 * ## 閾値の置き方
 *
 * `閉 + fraction × (開 − 閉)`。既定fraction=0.30で、**中点ではなく閉側**。
 * 眠気の閉眼は完全閉眼ではなく「うつろ」なので、閉側に寄せないと拾えない
 * (PERCLOSの「80%閉じたら閉眼」と同じ考え方)。
 * 正規化開眼比では開=1.0なので、閉0.43なら 0.43 + 0.30×0.57 = **0.60**。
 *
 * 画像・landmark・生フレームは保持しない(11_DAY_SMARTPHONE_MVP_PLAN.md 3.3)。
 */
class EyeClosedCalibrationCollector(
    private val settings: DayMvpSettings,
    private val openProfile: CalibrationProfile,
) {
    /** (monotonicMs, 正規化開眼比の左右平均, 左の生値, 右の生値) */
    private data class Sample(val atMs: Long, val ratio: Float, val rawLeft: Float, val rawRight: Float)

    private val samples = ArrayList<Sample>()
    private val excludedByFlag = mutableMapOf<QualityFlag, Int>()
    private var totalFrameCount = 0
    private var lastMs: Long? = null

    val currentTotalFrameCount: Int get() = totalFrameCount
    val validFrameCount: Int get() = samples.size

    /** 同じフレーム(時刻が前より新しくないもの)は数えない。理由は[CalibrationCollector]と同じ(R-04)。 */
    fun addFrame(frame: FeatureFrame) {
        if (frame.monotonicTimeMs <= (lastMs ?: Long.MIN_VALUE)) return
        totalFrameCount += 1
        lastMs = frame.monotonicTimeMs
        if (!frame.quality.isValidForDecision) {
            frame.quality.flags.forEach { excludedByFlag[it] = (excludedByFlag[it] ?: 0) + 1 }
            return
        }
        val l = frame.eyeLeftOpen ?: return
        val r = frame.eyeRightOpen ?: return
        val ratio = normalizedMean(l, r) ?: return
        samples += Sample(frame.monotonicTimeMs, ratio, l, r)
    }

    /**
     * 直近の安定区間が3条件を満たしているか。
     * 呼び出し側は基本時間を過ぎたあと、これがtrueになった時点で止めてよい。
     */
    fun isSatisfied(): Boolean = evaluate() is Evaluation.Ok

    /**
     * 結果を確定する。
     *
     * 失敗理由は「いちばん直しやすいもの」を返す。
     * フレーム不足 → 目を閉じていない/遮られている → 不安定、の順。
     */
    fun finish(): CalibrationResult {
        return when (val e = evaluate()) {
            is Evaluation.Ok -> {
                // クランプしたあとでも**閉眼値より上**に置く。2026-09-14に上限0.70が
                // 閉眼値0.71を下回り、較正したのに目を閉じても閾値に届かない状態になった。
                val clamped = e.threshold.coerceIn(
                    settings.eyeClosedCalibrationThresholdMin,
                    settings.eyeClosedCalibrationThresholdMax,
                )
                val floor = e.closedRatio + settings.eyeClosedCalibrationMinMarginAboveClosed
                CalibrationResult.Success(
                    openProfile.copy(
                        eyeClosedLeftBaseline = e.rawLeft,
                        eyeClosedRightBaseline = e.rawRight,
                        eyeClosedOpenRatioThreshold = maxOf(clamped, floor).coerceAtMost(0.95f),
                    ),
                )
            }
            is Evaluation.Failed -> CalibrationResult.Failure(
                CalibrationFailure(
                    reason = e.reason,
                    validFrameCount = e.windowCount,
                    requiredFrameCount = settings.eyeClosedCalibrationMinStableFrames,
                    totalFrameCount = totalFrameCount,
                    excludedByFlag = excludedByFlag.toMap(),
                    observedClosedRatio = e.closedRatio,
                ),
            )
        }
    }

    private sealed interface Evaluation {
        data class Ok(
            val closedRatio: Float,
            val threshold: Float,
            val rawLeft: Float,
            val rawRight: Float,
        ) : Evaluation

        data class Failed(
            val reason: CalibrationFailureReason,
            val windowCount: Int,
            val closedRatio: Float?,
        ) : Evaluation
    }

    private fun evaluate(): Evaluation {
        val now = lastMs ?: return Evaluation.Failed(CalibrationFailureReason.INSUFFICIENT_VALID_FRAMES, 0, null)
        val cutoff = now - settings.eyeClosedCalibrationStableWindowMs
        val window = samples.filter { it.atMs >= cutoff }
        if (window.size < settings.eyeClosedCalibrationMinStableFrames) {
            return Evaluation.Failed(CalibrationFailureReason.INSUFFICIENT_VALID_FRAMES, window.size, null)
        }
        val ratios = window.map { it.ratio }.sorted()
        val closed = ratios.median()
        val gap = 1f - closed
        // 差の確認を先にする。閉じていなければ「安定していても」意味がない。
        if (gap < settings.eyeClosedCalibrationMinGap) {
            return Evaluation.Failed(CalibrationFailureReason.EYES_NOT_CLOSED_OR_OCCLUDED, window.size, closed)
        }
        val spread = ratios.quantile(0.75f) - ratios.quantile(0.25f)
        if (spread > settings.eyeClosedCalibrationMaxSpread) {
            return Evaluation.Failed(CalibrationFailureReason.UNSTABLE, window.size, closed)
        }
        val threshold = closed + settings.eyeClosedCalibrationFraction * gap
        return Evaluation.Ok(
            closedRatio = closed,
            threshold = threshold,
            rawLeft = window.map { it.rawLeft }.sorted().median(),
            rawRight = window.map { it.rawRight }.sorted().median(),
        )
    }

    /** 開眼較正の基準で正規化した左右平均。基準が0以下なら計算できない。 */
    private fun normalizedMean(rawLeft: Float, rawRight: Float): Float? {
        val bl = openProfile.eyeOpenLeftBaseline
        val br = openProfile.eyeOpenRightBaseline
        if (bl <= 0f || br <= 0f) return null
        return (rawLeft / bl + rawRight / br) / 2f
    }

    private fun List<Float>.median(): Float = quantile(0.5f)

    /** ソート済み前提の分位点(線形補間なし、最近傍)。 */
    private fun List<Float>.quantile(q: Float): Float {
        if (isEmpty()) return 0f
        val idx = ((size - 1) * q).toInt().coerceIn(0, size - 1)
        return this[idx]
    }
}
