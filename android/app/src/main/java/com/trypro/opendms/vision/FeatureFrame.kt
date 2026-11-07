package com.trypro.opendms.vision

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * 解析結果の正規化型。
 * 03_TECHNICAL_ARCHITECTURE.md 9.2のFeatureFrame定義に対応する。
 *
 * D0-004時点では[SignalQuality](D0-005で追加)がまだ無いため、品質フィルタ前の生の計測値のみを持つ。
 * `landmarks`はoverlay描画専用。閉眼/脇見等の判定ロジックはD0-005以降でこの型を経由させ、
 * `landmarks`に直接依存させない方針にする(センサー非依存の抽象を壊さないため)。
 */
data class FeatureFrame(
    val monotonicTimeMs: Long,
    val wallClockUtcMs: Long,
    val faceDetected: Boolean,
    /**
     * 顔検出confidence。MediaPipe Face LandmarkerのFaceLandmarkerResultには
     * 検出confidenceを直接返すフィールドがないため、D0-004時点ではnull固定。
     * D0-005のSignalQuality設計時に代替指標(landmark presence等)を検討する。
     */
    val confidence: Float?,
    /** landmarksのx方向の広がり(max-min)を画像幅で割った値。顔サイズの簡易proxy。 */
    val faceSizeRatio: Float?,
    /** ARKit互換blendshape "eyeBlinkLeft/Right" から算出した開眼度(0=完全閉眼相当, 1=開眼)。 */
    val eyeLeftOpen: Float?,
    val eyeRightOpen: Float?,
    /**
     * ARKit互換blendshape "jawOpen" の生値(0=閉口, 1=大きく開口)。[com.trypro.opendms.domain.YawnDetector]の入力。
     *
     * MediaPipe公式のBlendshape V2モデルカードで52種のうちindex 25が`jawOpen`であることを確認済み。
     * `setOutputFaceBlendshapes(true)`は開眼度のために既に有効なので、この値を読むことによる
     * 推論コストの増加はない。開眼度と違い較正基準では正規化しない(絶対値のまま渡す)。
     */
    val jawOpen: Float?,
    /**
     * facialTransformationMatrixesから算出した頭部姿勢候補(度)。
     * 軸の符号対応はD0-011実機台本試験で要検証(D0-003_notes.md参照)。
     */
    val headYawDeg: Float?,
    val headPitchDeg: Float?,
    val headRollDeg: Float?,
    /** 解析画像の粗いサンプリングによる平均輝度(0..255)。 */
    val luminance: Float?,
    /** このフレームの推論時間(detectAsync投入〜結果コールバックまで, ms)。 */
    val inferenceTimeMs: Long,
    /** 直近2秒窓で実測した解析fps。 */
    val fps: Float,
    /** `android.os.PowerManager`の`THERMAL_STATUS_*`定数。 */
    val thermalStatus: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val landmarks: List<NormalizedLandmark>,
    /** [SignalQualityEvaluator]による品質評価。D0-007以降、閉眼/脇見タイマーはこれが
     *  `isValidForDecision == false`のフレームを継続時間へ加算してはならない(VIS-005)。 */
    val quality: SignalQuality,
    /**
     * D0-011追加検証: 前フレームとの間のyaw/pitch/roll合成角速度(度/秒)。
     * [QualityFlag.HEAD_ROTATING_RAPIDLY]の元になった値を診断ログで確認できるように保持する。
     * 前フレームが無い(直後・非連続)場合や未計算の場合はnull。
     */
    val headAngularVelocityDegPerSec: Float? = null,
    /**
     * 2026-08-31追加(**診断計測専用**): スマホ検出の観測結果。[PhoneDetector]が間引き間隔に
     * 達したフレームでだけ値を持ち、それ以外はnull。D1-020の実測ゲートが済むまで判定には使わない。
     */
    val phone: PhoneObservation? = null,
    /**
     * D0-011追加検証: このフレームに適用された取付回転角度(0/90/180/270)。
     * 実機での取付方向トラブルシュート用に診断ログへ残す。[MountRotation]参照。
     */
    val mountRotationDegrees: Int = 0,
) {
    companion object {
        // MediaPipe公式 face_mesh_connections.py の FACEMESH_LEFT_EYE / FACEMESH_RIGHT_EYE
        val LEFT_EYE_INDICES = intArrayOf(249, 263, 362, 373, 374, 380, 381, 382, 384, 385, 386, 387, 388, 390, 398, 466)
        val RIGHT_EYE_INDICES = intArrayOf(7, 33, 133, 144, 145, 153, 154, 155, 157, 158, 159, 160, 161, 163, 173, 246)
    }
}
