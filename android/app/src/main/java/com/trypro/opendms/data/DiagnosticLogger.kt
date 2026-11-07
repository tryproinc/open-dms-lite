package com.trypro.opendms.data

import android.content.Context
import android.util.Log
import com.trypro.opendms.BuildConfig
import com.trypro.opendms.domain.CalibrationProfile
import com.trypro.opendms.domain.DmsOutput
import com.trypro.opendms.domain.FiredAlert
import com.trypro.opendms.domain.MonitoringAvailability
import com.trypro.opendms.vision.FaultReason
import com.trypro.opendms.vision.FeatureFrame
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant

/**
 * D0-011の実地試験のために、1運行分の診断値をできるだけ広くCSVへ記録する。
 * 06_PRIVACY_SECURITY_OPERATIONS.md「詳細診断サンプル: 端末 原則保存しない。開発ビルドのみ明示操作」
 * に従い、(1) `BuildConfig.DEBUG`のビルドでのみ有効にし、(2) 「運行開始」という明示操作に紐づけて
 * (開始〜終了の間だけ)記録する。画像・landmark座標・GPS座標は一切書き込まない
 * (書き込むのはFeatureFrame/DmsOutputが既に持つスカラー値のみ)。
 *
 * 保存先は`getExternalFilesDir`配下(アプリ専用領域、パーミッション不要)。PCへUSB接続すれば
 * `Android/data/com.trypro.opendms/files/diagnostics/`から取り出せる。
 */
class DiagnosticLogger(private val context: Context) {

    private var writer: BufferedWriter? = null
    private var currentSessionId: String? = null

    val isEnabled: Boolean get() = BuildConfig.DEBUG

    fun start(sessionId: String) {
        if (!isEnabled) return
        stop()
        val dir = File(context.getExternalFilesDir(null), "diagnostics")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "session_${sessionId}.csv")
        try {
            val w = BufferedWriter(FileWriter(file, false))
            w.write(HEADER)
            w.newLine()
            writer = w
            currentSessionId = sessionId
        } catch (e: Exception) {
            Log.e(TAG, "failed to open diagnostic log ${file.absolutePath}", e)
        }
    }

    /**
     * @param speedKph 現在の車速(km/h)。取得できていなければnull。**緯度経度は記録しない。**
     * @param faultReason 故障を検知していればその種類(ASV眠気4章/脇見4章)。
     */
    fun logFrame(
        frame: FeatureFrame,
        output: DmsOutput?,
        firedAlerts: List<FiredAlert>,
        unavailableNoticeFired: Boolean,
        speedKph: Float? = null,
        faultReason: FaultReason? = null,
        /**
         * この行の判定に使った較正値(2026-09-14追加)。
         * 較正はカメラを差し替えるたびに上書きされ、あとから「その運行でどの閾値だったか」が
         * 分からなくなった(同日に3回較正し直していた)。行ごとに残しておく。
         */
        calibration: CalibrationProfile? = null,
        /** 警告を出し始めているか。falseの行の`firedAlerts`は鳴らしていない。 */
        alertsArmed: Boolean? = null,
    ) {
        val w = writer ?: return
        try {
            val fired = firedAlerts.joinToString(";") { "${it.type.name}:${it.severity.name}" }
            val row = listOf(
                isoTimestamp(frame.wallClockUtcMs),
                frame.monotonicTimeMs,
                frame.faceDetected,
                frame.faceSizeRatio.orEmpty(),
                frame.eyeLeftOpen.orEmpty(),
                frame.eyeRightOpen.orEmpty(),
                frame.jawOpen.orEmpty(),
                frame.headYawDeg.orEmpty(),
                frame.headPitchDeg.orEmpty(),
                frame.headRollDeg.orEmpty(),
                frame.headAngularVelocityDegPerSec.orEmpty(),
                frame.mountRotationDegrees,
                frame.luminance.orEmpty(),
                frame.fps,
                frame.inferenceTimeMs,
                frame.thermalStatus,
                frame.quality.score,
                frame.quality.flags.joinToString("|") { it.name },
                frame.quality.isValidForDecision,
                output?.eyeClosureDurationMs ?: "",
                output?.distractionDurationMs ?: "",
                output?.distractionRawDurationMs ?: "",
                output?.nodding?.reversalCount ?: "",
                output?.nodding?.meanEyeOpenRatio?.toString() ?: "",
                output?.nodding?.isNodding ?: "",
                output?.yawn?.currentOpenDurationMs ?: "",
                output?.yawn?.currentPeakJawOpen ?: "",
                output?.yawn?.countInWindow ?: "",
                output?.yawn?.isFrequent ?: "",
                output?.yawn?.yawnCompleted ?: "",
                speedKph?.toString() ?: "",
                output?.distractionSpeedGateOpen ?: "",
                faultReason?.name ?: "",
                frame.phone?.detected ?: "",
                frame.phone?.score?.toString() ?: "",
                frame.phone?.areaRatio?.toString() ?: "",
                output?.availability?.name ?: MonitoringAvailability.NORMAL.name,
                fired,
                unavailableNoticeFired,
                // 2026-09-11追加。既存の列位置を変えないよう末尾に足す。
                output?.nodding?.pitchRangeDeg?.toString() ?: "",
                output?.nodding?.yawRangeDeg?.toString() ?: "",
                output?.nodding?.isOpenEyeCandidate ?: "",
                calibration?.eyeOpenLeftBaseline?.toString() ?: "",
                calibration?.eyeOpenRightBaseline?.toString() ?: "",
                calibration?.eyeClosedLeftBaseline?.toString() ?: "",
                calibration?.eyeClosedRightBaseline?.toString() ?: "",
                calibration?.eyeClosedOpenRatioThreshold?.toString() ?: "",
                // 2026-09-27追加(docs/18 §14-21)。脇見の方向と、見失いを脇見として数えているか。
                output?.distractionDirection?.name ?: "",
                output?.distractionCarrying ?: "",
                // 2026-10-01追加(docs/21 R-21)。脇見の領域(手元/側方)。
                output?.distractionZone?.name ?: "",
                // 2026-10-16追加。falseの間のfiredAlertsは鳴らしていない。
                alertsArmed ?: "",
            ).joinToString(",")
            w.write(row)
            w.newLine()
            w.flush() // 実車試験中の異常終了でデータを失わないよう、毎行flushする。
        } catch (e: Exception) {
            Log.e(TAG, "failed to write diagnostic row", e)
        }
    }

    fun stop() {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "failed to close diagnostic log", e)
        } finally {
            writer = null
            currentSessionId = null
        }
    }

    private fun Float?.orEmpty(): String = this?.toString() ?: ""

    private fun isoTimestamp(utcMs: Long): String = Instant.ofEpochMilli(utcMs).toString()

    private companion object {
        const val TAG = "DiagnosticLogger"
        val HEADER = listOf(
            "timestampUtcIso", "monotonicMs",
            "faceDetected", "faceSizeRatio",
            "eyeLeftOpen", "eyeRightOpen", "jawOpen",
            "headYawDeg", "headPitchDeg", "headRollDeg",
            "headAngularVelocityDegPerSec",
            "mountRotationDegrees",
            "luminance", "fps", "inferenceMs", "thermalStatus",
            "qualityScore", "qualityFlags", "validForDecision",
            "eyeClosureDurationMs", "distractionDurationMs", "distractionRawDurationMs",
            "noddingReversals", "noddingMeanEyeOpen", "isNodding",
            "yawnOpenDurationMs", "yawnPeakJawOpen", "yawnCountInWindow", "yawnFrequent", "yawnCompleted",
            "speedKph", "speedGateOpen",
            "faultReason",
            "phoneDetected", "phoneScore", "phoneAreaRatio",
            "availability",
            "firedAlerts", "unavailableNoticeFired",
            "noddingPitchRangeDeg", "noddingYawRangeDeg", "noddingOpenEyeCandidate",
            "calEyeOpenL", "calEyeOpenR", "calEyeClosedL", "calEyeClosedR", "calEyeClosedThreshold",
            "distractionDirection", "distractionCarrying", "distractionZone",
            "alertsArmed",
        ).joinToString(",")
    }
}
