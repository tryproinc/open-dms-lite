package com.trypro.opendms.domain

import com.trypro.opendms.vision.FeatureFrame
import kotlin.math.abs

/**
 * [FeatureFrame]と較正基準([CalibrationProfile])から[DmsInput]を組み立てる。
 * `DayDmsStateMachine`をセンサー・MediaPipe固有型から分離するための変換層。
 */
object DmsInputMapper {

    /**
     * @param speedKph 現在の車速(km/h)。取得できない間はnull(速度ゲートを開いたまま扱う)。
     */
    fun from(
        frame: FeatureFrame,
        calibration: CalibrationProfile?,
        settings: DayMvpSettings,
        speedKph: Float? = null,
    ): DmsInput {
        // 閉眼判定は「個人較正した開眼基準に対する比率」がDRW-001の要件そのものなので、
        // 未較正(calibration==null)の間はeyesClosedを常にfalseとし、誤判定を避ける
        // (脇見側は較正前でも絶対角度で候補判定するSignalQualityEvaluatorの慣例に合わせ、
        // baseline未設定を0として扱うフォールバックにしている。両者で意図的に扱いを変えている)。
        val eyeLeftOpenNormalized = normalize(frame.eyeLeftOpen, calibration?.eyeOpenLeftBaseline)
        val eyeRightOpenNormalized = normalize(frame.eyeRightOpen, calibration?.eyeOpenRightBaseline)
        // 11_DAY_SMARTPHONE_MVP_PLAN.md 3.5の原案は「左右の正規化開眼比がともに0.45以下」(AND)
        // だったが、2026-08-29実機検証で左右の較正基準値に差(例: 左0.77・右0.67)が出るケースで
        // 片方の目(この例では右目)がMediaPipeのblendshape上ほとんど閾値を下回らず
        // (実測: 左目単独では全frame中66%が閉眼相当と判定されたのに対し右目単独ではわずか8%、
        // AND条件では同じく8%しか閉眼と判定されず10秒×2回の閉眼試験が実質無反応になった)、
        // 閉眼検知が機能しなくなる不具合を確認した。左右平均で判定する方式に変更したところ
        // 同じログで54%が閉眼相当と判定され、実際の閉眼期間を正しく捉えられることを確認したため、
        // AND方式から「左右平均が0.45以下」方式へ変更する(左右どちらか一方だけが極端に低い
        // 場合はもう一方が平均を押し上げるため、片目だけの誤検知は従来通り抑制される)。
        // 閾値は**閉眼較正があればその人の値**、無ければ設定の既定値(2026-09-11)。
        val closedThreshold = calibration?.eyeClosedOpenRatioThreshold ?: settings.eyeClosedOpenRatioThreshold
        val eyesClosed = eyeLeftOpenNormalized != null && eyeRightOpenNormalized != null &&
            (eyeLeftOpenNormalized + eyeRightOpenNormalized) / 2f <= closedThreshold

        val relativeYaw = frame.headYawDeg?.let { it - (calibration?.baselineYawDeg ?: 0f) }
        val relativePitch = frame.headPitchDeg?.let { it - (calibration?.baselinePitchDeg ?: 0f) }
        // NOTE: pitchの符号(上向き/下向きどちらが正か)はD0-003時点で未検証(D0-003_notes.md参照)。
        // 検証が済むまでの安全側の暫定実装として、符号を問わず絶対値で「大きく上下どちらかを向いた」
        // ことを脇見候補とする。D0-011で符号が確定したら「下方向のみ」に絞り込む。
        val distracted = (relativeYaw != null && abs(relativeYaw) >= settings.distractionYawDeg) ||
            (relativePitch != null && abs(relativePitch) >= settings.distractionPitchDownDeg)

        // 較正時より大きく縮んだ顔は誤検出として扱う([DayMvpSettings.faceSizeSuspectRatio])。
        val faceSizeBaseline = calibration?.faceSizeBaseline
        val suspectFace = faceSizeBaseline != null && faceSizeBaseline > 0f &&
            frame.faceSizeRatio != null &&
            frame.faceSizeRatio < faceSizeBaseline * settings.faceSizeSuspectRatio

        return DmsInput(
            monotonicMs = frame.monotonicTimeMs,
            validForDecision = frame.quality.isValidForDecision && !suspectFace,
            eyesClosed = eyesClosed,
            distracted = distracted,
            facialSignalReliable = frame.quality.isFacialSignalReliable,
            thermalStatus = frame.thermalStatus,
            relativePitchDeg = relativePitch,
            relativeYawDeg = relativeYaw,
            eyeOpenRatio = if (eyeLeftOpenNormalized != null && eyeRightOpenNormalized != null) {
                (eyeLeftOpenNormalized + eyeRightOpenNormalized) / 2f
            } else {
                null
            },
            // あくびは較正基準を使わない絶対値で判定するため、正規化せずそのまま渡す。
            jawOpen = frame.jawOpen,
            speedKph = speedKph,
        )
    }

    /** 較正済み基準に対する正規化開眼比(1.0=較正時の開眼相当)。未較正ならnullを返し、呼び出し側が扱う。 */
    private fun normalize(raw: Float?, baseline: Float?): Float? {
        if (raw == null) return null
        if (baseline == null || baseline <= 0f) return null
        return raw / baseline
    }
}
