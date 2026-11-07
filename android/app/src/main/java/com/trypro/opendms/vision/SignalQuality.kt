package com.trypro.opendms.vision

import com.trypro.opendms.domain.DayMvpSettings
import kotlin.math.abs

/**
 * 信号品質を構成する個別要因。02_MVP_PRODUCT_REQUIREMENTS.md VIS-004/VIS-005/VIS-006、
 * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.4(validForDecisionの条件)に対応する。
 *
 * D0-005のGateは「顔なし、顔小、片目、暗さ、低fpsを区別」であり、それぞれを独立したフラグとして
 * 区別できることを主目的とする。D0-006で3.4の全条件(相対頭部角度、閾値の一元化)を反映した。
 * 閾値は全て初期値であり、D0-011のNothing Phone (2a)台本試験で実測に基づき調整する。
 */
enum class QualityFlag {
    /** 顔が検出できていない。VIS-006: 2秒以上でLIMITED、5秒以上で監視不能警告(D0-007以降で状態機械に反映)。 */
    NO_FACE,
    /** 顔幅が画像に対して小さすぎる。02_MVP_PRODUCT_REQUIREMENTS.md: 顔幅は入力画像の概ね20%以上が条件。 */
    FACE_TOO_SMALL,

    /**
     * 顔幅が画像に対して大きすぎる(カメラに近すぎる)。
     * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.4「顔幅がframe幅の20〜60%目安」の上限側。
     */
    FACE_TOO_LARGE,
    /**
     * 片目しか見えていないとみなせる状態。MediaPipeのblendshapeは頭部が横向きでも両目分のスコアを
     * 返してしまい信頼できないため、head yawの大きさを代替指標(proxy)として使う暫定実装。
     * DRW-005: 「片目だけ」を長時間閉眼と誤認しないための入力に使う想定。
     */
    ONE_EYE_ONLY,
    /** 平均輝度が低すぎる(暗所・逆光等)。 */
    LOW_LIGHT,
    /** 解析fpsがVIS-008の目標(10fps以上)を下回っている。 */
    LOW_FPS,
    /** Android thermal statusがMODERATE以上。VIS-007: 熱状態が深刻なら推論頻度を下げる対象。 */
    THERMAL_DEGRADED,
    /**
     * 較正基準に対する相対頭部角度(yaw/pitch)が11_DAY_SMARTPHONE_MVP_PLAN.md 3.4の許容範囲
     * (yaw 45°, pitch 35°)を超えている。`ONE_EYE_ONLY`より広い(=より寛容な)範囲で、
     * これを超えると判定に一切使わない「大きな頭部移動」を表す。
     */
    HEAD_ANGLE_TOO_LARGE,

    /**
     * 頭部の回転速度(yaw/pitch/rollの合成角速度)が閾値を超えている。
     *
     * 2026-08-29実機検証で判明: 目を閉じ続けていても首を軽く動かす(数百ms間でyaw/pitch/rollが
     * 合計30〜40°/s程度変化する)と、MediaPipeのeyeBlink blendshapeスコアが一時的に
     * 実際の開眼度より高く(開眼寄りに)出ることがあり、`eyesClosed`判定が短時間falseになって
     * 閉眼継続タイマーが途切れる原因になっていた。この現象は`validForDecision`はtrueのまま
     * 起きるため、既存の「品質欠損300ms以内は継続扱い」(11_DAY_SMARTPHONE_MVP_PLAN.md 3.5)
     * だけでは救えていなかった。そこで頭部回転が速すぎる間のフレームを品質無効とすることで、
     * 同じ300ms猶予の仕組みでこの種のノイズも吸収できるようにする。
     */
    HEAD_ROTATING_RAPIDLY,
}

/**
 * 1フレーム分の信号品質評価結果。
 * `isValidForDecision`が11_DAY_SMARTPHONE_MVP_PLAN.md 3.4のvalidForDecision条件に対応する。
 * VIS-005: これがfalseのサンプルは閉眼・脇見の継続時間タイマーへ加算してはならない(D0-007で使用)。
 */
data class SignalQuality(
    val score: Float,
    val flags: Set<QualityFlag>,
    /**
     * `score >= DayMvpSettings.qualityValidThreshold && NO_FACE !in flags`を[SignalQualityEvaluator]が
     * 計算して渡す。設定で閾値を変更できるようにするため、ここではハードコードした定数と比較しない
     * (以前はこのクラス内の固定定数と比較していたためsettingsを変更しても効かなかった不具合があった)。
     */
    val isValidForDecision: Boolean,
    /**
     * **顔の表情特徴**(開眼度・口の開きなどのblendshape)がこのフレームで信頼できるか。
     *
     * 顔が正面を向いていない間はfalse:
     * - `HEAD_ROTATING_RAPIDLY` … 回転の最中でblendshapeがぶれる
     * - `ONE_EYE_ONLY` … 片目が隠れて開眼度が読めない
     * - `HEAD_ANGLE_TOO_LARGE` … 表情がほぼ読めない角度
     *
     * これを見るのは**閉眼・こっくり・あくび**(顔の表情特徴に依存する判定)。
     * **脇見は見ない** ── 脇見は頭部角度だけで判定でき、頭部角度はこの状態でも有効だから。
     * 詳細は[DmsInput.facialSignalReliable]。
     */
    val isFacialSignalReliable: Boolean = true,
)

object SignalQualityEvaluator {
    // 各要因の減点幅(暫定)。合計で1.0を割り込めばqualityScoreは0にfloorされる。
    private const val PENALTY_FACE_TOO_SMALL = 0.4f
    private const val PENALTY_FACE_TOO_LARGE = 0.4f
    /**
      * 顔が横を向いて片目しか見えない状態。
      *
      * **減点しない(0)。** 2026-09-01の実機ログ(session_b4ce5f53)で、
      * 脇見が全く成立しない原因がこれだと確定したため。
      *
      * - 脇見の閾値は相対|yaw| >= 25°(`distractionYawDeg`)
      * - このフラグが立つのは相対|yaw| > 30°(`oneEyeOnlyYawDeg`)
      * - 旧・減点0.35 → score 0.65 < 閾値0.70 → **判定無効**
      *
      * つまり「判定に使えたまま脇見と言える」帯は**25〜30°のわずか5°**しかなく、
      * 実測634フレーム中その帯に入ったのは**1フレームだけ**だった。
      * 脇見候補の連続区間は6本(最長4.1秒)あったが、**判定可のフレームは0**。
      * 6秒の脇見警告が出る余地が原理的に無かった。
      *
      * 顔が横を向いている間に信用できないのは**顔の表情特徴(開眼度・口の開き)**であって、
      * **頭部角度そのものは有効**である。むしろ頭部角度は脇見判定の信号そのものなので、
      * これで判定を止めるのは本末転倒だった。抑制は[SignalQuality.isFacialSignalReliable]で行う。
      */
     private const val PENALTY_ONE_EYE_ONLY = 0f
    private const val PENALTY_LOW_LIGHT = 0.35f
    private const val PENALTY_LOW_FPS = 0.3f
    /**
     * **減点しない(0)**(2026-10-17、Full の docs/23 §10-6)。Nothing Phone (2a)は室温でもMODERATE〜SEVEREを返し、
     * 常に0.2減点されていた。処理の遅れは[QualityFlag.LOW_FPS]が見ている。フラグは診断CSVに残す。
     */
    private const val PENALTY_THERMAL_DEGRADED = 0f
    /**
      * 相対|yaw| > 60° / 相対|pitch| > 70°。**減点しない(0)。**
      * 理由は[PENALTY_ONE_EYE_ONLY]と同じ。抑制は[SignalQuality.isFacialSignalReliable]で行う。
      *
      * この角度では顔の表情特徴はほぼ読めないが、「前方を見ていない」ことだけは確実に言える。
      * 顔が全く取れなくなれば`NO_FACE`でscore=0になるので、そちらは従来どおり判定から外れる。
      */
     private const val PENALTY_HEAD_ANGLE_TOO_LARGE = 0f
    /**
     * 頭部回転中に信用できないのは「顔の表情特徴だけ」で、頭部角度そのものは有効。
     * したがってqualityScoreを一切下げない(=`isValidForDecision`に影響させない)。
     * 抑制は[SignalQuality.isFacialSignalReliable]だけで行う。
     *
     * **[PENALTY_ONE_EYE_ONLY]・[PENALTY_HEAD_ANGLE_TOO_LARGE]も同じ考え方に揃えた
     * (2026-09-01)。** この3つは「顔が正面を向いていない」という同じ事象の別の面であり、
     * 1つだけ0にしても残り2つが判定を無効化するので、脇見は成立しないままだった。
     *
     * 2026-08-29: 当初0.5(判定ごと無効化)→0.1と下げたが、0.1でも
     * 「LOW_FPS(0.3)+回転(0.1)=0.6 < 閾値0.70」のように他フラグとの組み合わせで
     * 有効性を反転させ得ることがコードレビューで判明したため0とした。
     */
    private const val PENALTY_HEAD_ROTATING_RAPIDLY = 0f

    /**
     * @param calibrationBaselineYawDeg 較正済みの基準yaw(度)。未較正ならnull(その場合は絶対角度を
     *   そのまま相対角度として扱う。較正前のPREFLIGHT/CALIBRATING中でも「大きく横を向いている」ことは
     *   検出できるようにするための実用上のフォールバック)。
     */
    fun evaluate(
        settings: DayMvpSettings,
        faceDetected: Boolean,
        faceSizeRatio: Float?,
        headYawDeg: Float?,
        headPitchDeg: Float?,
        calibrationBaselineYawDeg: Float?,
        calibrationBaselinePitchDeg: Float?,
        luminance: Float?,
        fps: Float,
        thermalStatus: Int,
        /**
         * yaw/pitch/rollの合成角速度(度/秒)。前フレームが無い等で計算できない場合はnull
         * (nullの場合はHEAD_ROTATING_RAPIDLYを立てない=フォールバックとして安全側=判定に使う)。
         */
        headAngularVelocityDegPerSec: Float? = null,
    ): SignalQuality {
        val flags = mutableSetOf<QualityFlag>()

        if (!faceDetected) {
            flags += QualityFlag.NO_FACE
        }
        if (faceSizeRatio != null && faceSizeRatio < settings.minFaceSizeRatio) {
            flags += QualityFlag.FACE_TOO_SMALL
        }
        // 11_DAY_SMARTPHONE_MVP_PLAN.md 3.4「顔幅がframe幅の20〜60%目安」。
        // D0-006では下限のみ実装し上限が未配線だった(設定値だけ存在)。
        if (faceSizeRatio != null && faceSizeRatio > settings.maxFaceSizeRatio) {
            flags += QualityFlag.FACE_TOO_LARGE
        }

        val relativeYaw = headYawDeg?.let { it - (calibrationBaselineYawDeg ?: 0f) }
        val relativePitch = headPitchDeg?.let { it - (calibrationBaselinePitchDeg ?: 0f) }

        if (relativeYaw != null && abs(relativeYaw) > settings.oneEyeOnlyYawDeg) {
            flags += QualityFlag.ONE_EYE_ONLY
        }
        if ((relativeYaw != null && abs(relativeYaw) > settings.maxAbsRelativeYawDeg) ||
            (relativePitch != null && abs(relativePitch) > settings.maxAbsRelativePitchDeg)
        ) {
            flags += QualityFlag.HEAD_ANGLE_TOO_LARGE
        }
        if (luminance != null && luminance < settings.lowLightLuminance) {
            flags += QualityFlag.LOW_LIGHT
        }
        // fps<=0はまだ計測窓(2秒)が満ちていない起動直後の状態であり、低fpsと誤認しない。
        if (fps > 0f && fps < settings.minValidFps) {
            flags += QualityFlag.LOW_FPS
        }
        if (thermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE) {
            flags += QualityFlag.THERMAL_DEGRADED
        }
        if (headAngularVelocityDegPerSec != null && headAngularVelocityDegPerSec > settings.maxHeadAngularVelocityDegPerSec) {
            flags += QualityFlag.HEAD_ROTATING_RAPIDLY
        }

        val score = computeScore(flags)
        return SignalQuality(
            score = score,
            flags = flags,
            isValidForDecision = score >= settings.qualityValidThreshold && QualityFlag.NO_FACE !in flags,
            isFacialSignalReliable = QualityFlag.HEAD_ROTATING_RAPIDLY !in flags &&
                QualityFlag.ONE_EYE_ONLY !in flags &&
                QualityFlag.HEAD_ANGLE_TOO_LARGE !in flags,
        )
    }

    private fun computeScore(flags: Set<QualityFlag>): Float {
        if (QualityFlag.NO_FACE in flags) return 0f
        var score = 1f
        if (QualityFlag.FACE_TOO_SMALL in flags) score -= PENALTY_FACE_TOO_SMALL
        if (QualityFlag.FACE_TOO_LARGE in flags) score -= PENALTY_FACE_TOO_LARGE
        if (QualityFlag.ONE_EYE_ONLY in flags) score -= PENALTY_ONE_EYE_ONLY
        if (QualityFlag.LOW_LIGHT in flags) score -= PENALTY_LOW_LIGHT
        if (QualityFlag.LOW_FPS in flags) score -= PENALTY_LOW_FPS
        if (QualityFlag.THERMAL_DEGRADED in flags) score -= PENALTY_THERMAL_DEGRADED
        if (QualityFlag.HEAD_ANGLE_TOO_LARGE in flags) score -= PENALTY_HEAD_ANGLE_TOO_LARGE
        if (QualityFlag.HEAD_ROTATING_RAPIDLY in flags) score -= PENALTY_HEAD_ROTATING_RAPIDLY
        return score.coerceIn(0f, 1f)
    }
}
