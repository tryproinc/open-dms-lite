package com.trypro.opendms.vision

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.roundToInt
import android.graphics.RectF
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.core.content.getSystemService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.trypro.opendms.domain.CalibrationProfile
import com.trypro.opendms.domain.DayMvpSettings
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.atan2

/**
 * MediaPipe Face Landmarker(LIVE_STREAM)で前面カメラ映像を解析し、[FeatureFrame]を生成する。
 *
 * 実装はGoogle公式サンプル(mediapipe-samples/examples/face_landmarker/android の
 * FaceLandmarkerHelper.kt, 2026-08-28時点)のImageProxy→Bitmap→MPImage変換方式に倣う。
 * ImageAnalysisの出力フォーマットは呼び出し側で`OUTPUT_IMAGE_FORMAT_RGBA_8888`にしておくこと
 * (`imageProxy.planes[0].buffer`を単一プレーンとしてそのままコピーするため)。
 *
 * 画像・landmark座標を一切ファイル/DBへ保存しない([[VIS-003]])。解析後の`Bitmap`はローカル変数のみ。
 */
class MediaPipeFaceAnalyzer(
    context: Context,
    initialSettings: DayMvpSettings = DayMvpSettings.DAY_MVP0_DEFAULT,
    private val onResult: (FeatureFrame?) -> Unit,
) {
    private val powerManager: PowerManager? = context.getSystemService()

    /**
     * 実地試験前に設定画面から閾値を変更できるようにするため、`settings`は生成後も更新できる
     * (`calibrationBaseline`と同じ理由で`@Volatile`にする)。
     */
    @Volatile
    var settings: DayMvpSettings = initialSettings

    /**
     * 較正済みプロファイル。未較正の間はnull(この場合SignalQualityは絶対角度を相対角度として扱う)。
     * Compose(メインスレッド)からの書き込みとMediaPipeの結果コールバックスレッドからの読み取りが
     * 交差するため`@Volatile`でスレッド間の可視性を保証する。
     */
    @Volatile
    var calibrationBaseline: CalibrationProfile? = null

    /**
     * D0-011追加検証: スマホの物理的な取付回転角度(0/90/180/270)。DayMvpScreen側で
     * MountOrientationSensor/MountOrientationStoreから解決した値をComposeのSideEffectで反映する
     * (calibrationBaselineと同じ理由で@Volatile)。詳細は[MountRotation]参照。
     */
    @Volatile
    var mountRotationDegrees: Int = 0

    /**
     * CameraX経路で使い回すBitmap。**触ってよいのはCameraXの解析スレッド1本だけ。**
     * 毎フレーム確保するとネイティブSIGSEGVの引き金になる([analyze]のコメント参照)。
     */
    private var cameraBitmapBuffer: Bitmap? = null
    private var cameraRotatedBitmap: Bitmap? = null

    /**
     * 2026-08-31追加: 故障検知(ASV眠気4章/脇見4章)。設定されていればフレーム投入・結果受信・
     * 推論エラーを通知する。時計は[SystemClock.elapsedRealtime]で統一する
     * (`monotonicTimeMs`に使っている`uptimeMillis`と違い、端末のdeep sleep中も進むため、
     * 「結果が返ってこない」の判定に適している)。
     */
    @Volatile
    var faultMonitor: FaultMonitor? = null

    /**
     * 2026-08-31追加(**診断計測専用**): スマホ検出([PhoneDetector])。設定されていれば
     * 間引き間隔ごとに同期実行する。release buildでは設定しない。
     */
    @Volatile
    var phoneDetector: PhoneDetector? = null

    // detectAsyncへ渡すframeTimeMs(=SystemClock.uptimeMillis())をキーに、投入時刻(elapsedRealtime)を
    // 記録する。LIVE_STREAMは複数フレームが同時にin-flightになり得るため、共有の1変数ではなく
    // フレーム単位で対応付けて推論時間を正しく計測する。
    private val submittedAtElapsedRealtimeMs = ConcurrentHashMap<Long, Long>()
    // 上記と同じ理由(LIVE_STREAMの複数フレーム同時in-flight)で、輝度もframeTimeMsキーで対応付ける。
    private val pendingLuminance = ConcurrentHashMap<Long, Float>()
    // スマホ検出の観測結果も同じくframeTimeMsキーで対応付ける(間引くため一部フレームにしか無い)。
    private val pendingPhone = ConcurrentHashMap<Long, PhoneObservation>()

    // 直近2秒窓のfps計測用(3.4の「直近2秒の解析fps」に対応)。
    private var fpsWindowStartMs = SystemClock.elapsedRealtime()
    private var fpsWindowCount = 0
    private var lastFps = 0f

    // D0-011追加検証: 頭部の合成角速度(HEAD_ROTATING_RAPIDLY判定用)を求めるため、
    // 直前フレームのyaw/pitch/rollとそのmonotonic時刻を保持する。
    private var previousAnglesDeg: Triple<Float, Float, Float>? = null
    private var previousAngleTimestampMs: Long? = null

    private val faceLandmarker: FaceLandmarker = FaceLandmarker.createFromOptions(
        context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .build(),
            )
            .setMinFaceDetectionConfidence(MIN_CONFIDENCE)
            .setMinTrackingConfidence(MIN_CONFIDENCE)
            .setMinFacePresenceConfidence(MIN_CONFIDENCE)
            .setNumFaces(1)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(::handleResult)
            .setErrorListener(::handleError)
            .build(),
    )

    /**
     * [CameraXVisionSource]の`onFrame`から呼ぶ。呼び出し側は`imageProxy`をこの関数の後にcloseしてよい
     * (ここではBitmapへコピーするだけで、非同期の`detectAsync`にはimageProxyの参照を渡さない)。
     */
    fun analyze(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        pruneStaleSubmissions()

        val frameTimeMs = SystemClock.uptimeMillis()

        // **毎フレームBitmapを確保しない。**
        //
        // 2026-09-07にネイティブSIGSEGVで落ちた(dropboxのtombstone:
        // `libmediapipe_tasks_jni.so` の `nativeCreateRgbaImage` → `analyze+1300`)。
        // ここは17〜22fpsで回るので、`bitmapBuffer`と`rotatedBitmap`を毎フレーム作ると
        // **フルフレームARGBを1秒に40枚**確保することになる(640x480で約48MB/秒)。
        //
        // 使い回して安全な理由: `analyze`はCameraXの解析スレッド1本からしか呼ばれず、
        // `detectAsync`は`nativeCreateRgbaImage`で**同期的に画素をコピーしてから**返る。
        // 戻った時点でBitmapの中身はもう要らないので、次のフレームで上書きしてよい。
        var buffer = cameraBitmapBuffer
        if (buffer == null || buffer.width != imageProxy.width || buffer.height != imageProxy.height) {
            buffer?.recycle()
            buffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            cameraBitmapBuffer = buffer
        }
        val bitmapBuffer = buffer
        bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

        // D0-011追加検証: スマホの取付回転(上下逆・横向き)に対応するため、CameraXが返す
        // 回転角に取付回転角を合成する(導出はMountRotationのKDoc参照、実機4方向要検証)。
        val totalRotation = MountRotation.frontAnalysisRotation(imageProxy.imageInfo.rotationDegrees, mountRotationDegrees)
        val swapped = MountRotation.swapsDimensions(totalRotation)
        // ミラーの基準点は「回転後」の幅・高さを使う必要がある(90/270°では入れ替わる)。
        // 元のコードは回転前のwidth/heightを固定で使っており、取付回転が0°以外になると
        // ミラーの基準点がずれる潜在バグだったため、回転量に応じて正しい基準点を選ぶ。
        val pivotX = if (swapped) imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val pivotY = if (swapped) imageProxy.width.toFloat() else imageProxy.height.toFloat()

        val matrix = Matrix().apply {
            postRotate(totalRotation.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, pivotX, pivotY)
            }
        }
        // `Bitmap.createBitmap(src, ..., matrix, true)` と同じ結果を、確保せずに作る。
        // あの関数は変換後の外接矩形が原点に来るよう自動で平行移動するので、それを自分で行う。
        val mapped = RectF(0f, 0f, bitmapBuffer.width.toFloat(), bitmapBuffer.height.toFloat())
        matrix.mapRect(mapped)
        matrix.postTranslate(-mapped.left, -mapped.top)
        val dstW = mapped.width().roundToInt().coerceAtLeast(1)
        val dstH = mapped.height().roundToInt().coerceAtLeast(1)
        var rotated = cameraRotatedBitmap
        if (rotated == null || rotated.width != dstW || rotated.height != dstH) {
            rotated?.recycle()
            rotated = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            cameraRotatedBitmap = rotated
        }
        val rotatedBitmap = rotated
        Canvas(rotatedBitmap).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(bitmapBuffer, matrix, null)
        }

        val luminance = sampleAverageLuminance(rotatedBitmap)

        // スマホ検出(診断計測専用)。Face Landmarkerへ投入する前に、同じ解析スレッド上で
        // 同期実行する。並行して走るMediaPipeグラフを常に1本に保つための構成
        // (理由はPhoneDetectorのKDoc「なぜ同期実行なのか」を参照)。
        phoneDetector?.detectIfDue(rotatedBitmap, SystemClock.elapsedRealtime())?.let {
            pendingPhone[frameTimeMs] = it
        }

        val mpImage: MPImage = BitmapImageBuilder(rotatedBitmap).build()

        val submittedAt = SystemClock.elapsedRealtime()

        submittedAtElapsedRealtimeMs[frameTimeMs] = submittedAt
        pendingLuminance[frameTimeMs] = luminance
        faultMonitor?.onFrameSubmitted(submittedAt)
        faceLandmarker.detectAsync(mpImage, frameTimeMs)
    }

    private fun handleResult(result: FaceLandmarkerResult, image: MPImage) {
        faultMonitor?.onResult(SystemClock.elapsedRealtime())
        val submittedAt = submittedAtElapsedRealtimeMs.remove(result.timestampMs())
        val inferenceTimeMs = if (submittedAt != null) {
            SystemClock.elapsedRealtime() - submittedAt
        } else {
            -1L
        }
        val luminance = pendingLuminance.remove(result.timestampMs())
        val phone = pendingPhone.remove(result.timestampMs())

        onResult(toFeatureFrame(result, image, inferenceTimeMs, luminance, phone))
    }

    private fun handleError(error: RuntimeException) {
        Log.e(TAG, "FaceLandmarker detectAsync failed", error)
        // errorListenerは単発フレームの失敗でも呼ばれるため、1回では故障扱いにしない。
        // 連続回数の判定はFaultMonitorが持つ(既定10回連続)。
        faultMonitor?.onInferenceError()
        onResult(null)
    }

    fun close() {
        faceLandmarker.close()
        // 使い回していたBitmapを解放する(解析スレッドはもう動いていない前提)。
        cameraBitmapBuffer?.recycle()
        cameraBitmapBuffer = null
        cameraRotatedBitmap?.recycle()
        cameraRotatedBitmap = null
    }

    private fun toFeatureFrame(
        result: FaceLandmarkerResult,
        image: MPImage,
        inferenceTimeMs: Long,
        luminance: Float?,
        phone: PhoneObservation? = null,
    ): FeatureFrame {
        updateFps()

        val landmarks = result.faceLandmarks().firstOrNull().orEmpty()
        val faceDetected = landmarks.isNotEmpty()

        val faceSizeRatio = if (faceDetected) {
            val xs = landmarks.map { it.x() }
            (xs.max() - xs.min())
        } else {
            null
        }

        val blendshapes = result.faceBlendshapes().orElse(null)?.firstOrNull()
        val eyeBlinkLeft = blendshapes?.firstOrNull { it.categoryName() == "eyeBlinkLeft" }?.score()
        val eyeBlinkRight = blendshapes?.firstOrNull { it.categoryName() == "eyeBlinkRight" }?.score()
        // あくび検出([com.trypro.opendms.domain.YawnDetector])用。同じblendshape結果から読むだけなので
        // 推論コストは増えない。開眼度と違い1f-xの反転はしない(jawOpenは大きいほど口が開いている)。
        val jawOpen = blendshapes?.firstOrNull { it.categoryName() == "jawOpen" }?.score()

        val matrix = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
        val (yaw, pitch, roll) = matrix?.let { eulerAnglesDegFromColumnMajor4x4(it) } ?: Triple(null, null, null)
        val angularVelocityDegPerSec = computeAngularVelocityDegPerSec(yaw, pitch, roll, result.timestampMs())

        val thermalStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        val baseline = calibrationBaseline
        val quality = SignalQualityEvaluator.evaluate(
            settings = settings,
            faceDetected = faceDetected,
            faceSizeRatio = faceSizeRatio,
            headYawDeg = yaw,
            headPitchDeg = pitch,
            calibrationBaselineYawDeg = baseline?.baselineYawDeg,
            calibrationBaselinePitchDeg = baseline?.baselinePitchDeg,
            luminance = luminance,
            fps = lastFps,
            thermalStatus = thermalStatus,
            headAngularVelocityDegPerSec = angularVelocityDegPerSec,
        )

        return FeatureFrame(
            monotonicTimeMs = result.timestampMs(),
            wallClockUtcMs = System.currentTimeMillis(),
            faceDetected = faceDetected,
            confidence = null,
            faceSizeRatio = faceSizeRatio,
            eyeLeftOpen = eyeBlinkLeft?.let { 1f - it },
            eyeRightOpen = eyeBlinkRight?.let { 1f - it },
            jawOpen = jawOpen,
            phone = phone,
            headYawDeg = yaw,
            headPitchDeg = pitch,
            headRollDeg = roll,
            luminance = luminance,
            inferenceTimeMs = inferenceTimeMs,
            fps = lastFps,
            thermalStatus = thermalStatus,
            imageWidth = image.width,
            imageHeight = image.height,
            landmarks = landmarks,
            quality = quality,
            headAngularVelocityDegPerSec = angularVelocityDegPerSec,
            mountRotationDegrees = mountRotationDegrees,
        )
    }

    /**
     * 前フレームとのyaw/pitch/roll差分から合成角速度(度/秒)を求める(D0-011追加検証、
     * [QualityFlag.HEAD_ROTATING_RAPIDLY]用)。副作用として次回計算のために今回値を保持する。
     * yaw/pitch/rollのいずれかがnull、前フレームが無い、または経過時間が短すぎる/長すぎる
     * (fpsの計測window外・非連続とみなせる)場合はnullを返す。
     */
    private fun computeAngularVelocityDegPerSec(
        yaw: Float?,
        pitch: Float?,
        roll: Float?,
        timestampMs: Long,
    ): Float? {
        val current = if (yaw != null && pitch != null && roll != null) Triple(yaw, pitch, roll) else null
        val previous = previousAnglesDeg
        val previousTimestampMs = previousAngleTimestampMs

        previousAnglesDeg = current
        previousAngleTimestampMs = timestampMs

        if (current == null || previous == null || previousTimestampMs == null) return null
        val dtSec = (timestampMs - previousTimestampMs) / 1000f
        // 逆行(タイムスタンプ異常)や極端に長い間隔(顔ロスト等で連続性が無い)は計算しない。
        if (dtSec <= 0f || dtSec > MAX_ANGULAR_VELOCITY_DT_SEC) return null

        val dYaw = current.first - previous.first
        val dPitch = current.second - previous.second
        val dRoll = current.third - previous.third
        val magnitudeDeg = kotlin.math.sqrt(dYaw * dYaw + dPitch * dPitch + dRoll * dRoll)
        return magnitudeDeg / dtSec
    }

    /**
     * `errorListener`は失敗したフレームのframeTimeMsを教えてくれないため、`handleResult`側の
     * `remove()`だけでは対応漏れがあり得る。推論に数秒もかからない前提で、それより十分長く
     * 残っているエントリはorphanとみなして間引く。これをしないと30分・12時間という長時間運用中に
     * `submittedAtElapsedRealtimeMs`/`pendingLuminance`が単調増加してメモリを圧迫し得る
     * (11_DAY_SMARTPHONE_MVP_PLAN.md PERF-002/PERF-008の長時間安定性要件に関わる)。
     */
    private fun pruneStaleSubmissions() {
        val now = SystemClock.elapsedRealtime()
        submittedAtElapsedRealtimeMs.entries.removeAll { (_, submittedAt) ->
            now - submittedAt > STALE_SUBMISSION_TIMEOUT_MS
        }
        // pendingLuminanceはsubmittedAtElapsedRealtimeMsと同じキー集合を使うため、
        // 削除されたキーに追従させる。
        pendingLuminance.keys.retainAll(submittedAtElapsedRealtimeMs.keys)
        pendingPhone.keys.retainAll(submittedAtElapsedRealtimeMs.keys)
    }

    /**
     * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.4は「直近2秒の解析fpsが10以上」を条件としている。
     * D0-006では1秒窓で実装していた(D0-006_notes.mdに既知差分として記録)ため、仕様どおり2秒窓へ揃える。
     */
    private fun updateFps() {
        fpsWindowCount += 1
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= FPS_WINDOW_MS) {
            lastFps = fpsWindowCount * 1000f / elapsed
            fpsWindowCount = 0
            fpsWindowStartMs = now
        }
    }

    /** 粗いグリッドサンプリングでBitmap全体をスキャンせずに平均輝度を概算する(D0-004: 計測用途)。 */
    private fun sampleAverageLuminance(bitmap: Bitmap): Float {
        val stepX = (bitmap.width / LUMINANCE_SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (bitmap.height / LUMINANCE_SAMPLE_GRID).coerceAtLeast(1)
        var sum = 0L
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                sum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
                count += 1
                x += stepX
            }
            y += stepY
        }
        return if (count > 0) sum.toFloat() / count else 0f
    }

    /**
     * `facialTransformationMatrixes()`が返すfloat[16]は列優先(COLUMN_MAJOR)の4x4行列
     * (mediapipe/framework/formats/matrix_data.proto のデフォルト。2026-08-28時点で一次情報確認済み)。
     * 回転部分(3x3, row-major換算: r[row][col] = m[col*4+row])からZYX分解でオイラー角を求め、
     * (yaw=左右, pitch=上下, roll=首の傾き)の順に返す。
     *
     * ## 軸の対応(2026-08-29に実機ログで確定。D0-003以来の未検証事項をここで解消)
     * ZYX分解 R = Rz(α)·Ry(β)·Rx(γ) の各成分は「回転軸」であって「人間から見た頭の動き」とは
     * 別物である点に注意that必要がある。MediaPipeの正準顔座標系(X:右, Y:上, Z:手前)では
     * 次の対応になる:
     *
     * | 分解成分 | 回転軸 | 人間の動作 |
     * |---|---|---|
     * | α = atan2(r10, r00) | Z軸 | 首を左右に**傾ける**(roll) |
     * | β = -asin(r20)      | Y軸 | 左右を**向く**(yaw) |
     * | γ = atan2(r21, r22) | X軸 | 上下を**向く/頷く**(pitch) |
     *
     * 当初この3つをそのまま(yaw, pitch, roll)として返していたため、実際には
     * roll→yaw欄、yaw→pitch欄、pitch→roll欄という**1つずつずれた割り当て**になっていた。
     * その結果:
     * - 左右を向く動作は「pitch欄」に出るため、yaw基準の脇見判定(±25°)が一切成立しなかった
     *   (実測でyaw欄の全変動幅は27.7°しかなく、内訳も首の傾きだった)。
     * - 上下を向く動作は「roll欄」に出るが、roll欄はどの判定にも使っていなかったため完全に無反応だった。
     *
     * 実測による裏付け(session_70b193d0の純粋な上下動=こっくり区間):
     * roll欄の振れ32.8°に対しpitch欄は8.1°しか動いておらず、上下動がroll欄に出ることを確認した。
     *
     * なお各軸の**符号**(上向きが正か負か)は引き続き未確定のため、判定側は引き続き絶対値で扱う
     * ([DmsInputMapper]参照)。
     */
    private fun eulerAnglesDegFromColumnMajor4x4(m: FloatArray): Triple<Float, Float, Float> {
        val r00 = m[0]; val r10 = m[1]; val r20 = m[2]
        val r21 = m[6]
        val r22 = m[10]

        // α: Z軸まわり = 首の傾き(roll)
        val rollRad = atan2(r10.toDouble(), r00.toDouble())
        // β: Y軸まわり = 左右を向く(yaw)
        val yawRad = -asin(r20.coerceIn(-1f, 1f).toDouble())
        // γ: X軸まわり = 上下を向く(pitch)
        val pitchRad = atan2(r21.toDouble(), r22.toDouble())

        return Triple(
            Math.toDegrees(yawRad).toFloat(),
            Math.toDegrees(pitchRad).toFloat(),
            Math.toDegrees(rollRad).toFloat(),
        )
    }

    private companion object {
        const val TAG = "MediaPipeFaceAnalyzer"
        const val MODEL_ASSET_PATH = "face_landmarker.task"
        const val MIN_CONFIDENCE = 0.5f
        const val LUMINANCE_SAMPLE_GRID = 20
        const val STALE_SUBMISSION_TIMEOUT_MS = 5_000L
        // これを超える間隔(顔ロスト・非連続な再検出等)は角速度計算に使わない。
        const val MAX_ANGULAR_VELOCITY_DT_SEC = 1.0f
        /** fps計測窓。11_DAY_SMARTPHONE_MVP_PLAN.md 3.4「直近2秒の解析fps」に合わせる。 */
        const val FPS_WINDOW_MS = 2_000L
    }
}
