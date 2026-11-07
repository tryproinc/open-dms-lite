package com.trypro.opendms.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.trypro.opendms.domain.DayMvpSettings

/**
 * スマホ検出の1回分の観測結果。診断CSVへそのまま出す。
 *
 * [areaRatio]がD1-020の実測ゲートの主役である。ダッシュに固定した前面カメラの画角に、
 * 運転者が手に持つスマホが**どれだけの大きさで写るか**が分からなければ、判定ロジックを
 * 組んでも意味がないため。
 */
data class PhoneObservation(
    /** `cell phone`を[DayMvpSettings.phoneDetectionScoreThreshold]以上のscoreで検出したか。 */
    val detected: Boolean,
    /** 検出した`cell phone`のうち最大scoreのもの。未検出ならnull。 */
    val score: Float?,
    /** 上記のbounding boxの面積が画像全体に占める比率。未検出ならnull。 */
    val areaRatio: Float?,
)

/**
 * MediaPipe ObjectDetectorで`cell phone`を検出する。**現時点では診断計測専用**であり、
 * 警告・イベント記録には使わない。
 *
 * ## ライセンス上の位置づけ
 * `com.google.mediapipe:tasks-vision`(**Apache-2.0**、POM原文で確認済み)に
 * `objectdetector`パッケージが同梱されており、**新しい依存を追加していない**。
 * 配布モデル`efficientdet_lite0.tflite`に同梱の`labels.txt`は90ラベルで、
 * **index 76 = `cell phone`** が実在することをモデル実体の検査で確認済み。
 * Ultralytics YOLO(AGPL-3.0)は使わない。
 *
 * ## なぜ同期実行なのか(重要)
 * Face Landmarkerと同じ`LIVE_STREAM`の非同期グラフをもう1本立てると、**2系統同時実行になる**。
 * D0-011で前面カメラ(約20fps)とUVC(約2fps)の2系統同時MediaPipe実行が
 * `Bitmap.createBitmap()`内でネイティブSIGSEGVを起こした実績があるため、その構成は取らない。
 *
 * 代わりに[RunningMode.IMAGE]の**同期`detect()`をCameraXの解析スレッド上でそのまま呼ぶ**。
 * これにより
 * - 並行して走るMediaPipeグラフが常に1本だけになる
 * - Face Landmarker用に既に作った`Bitmap`を使い回すので追加のBitmap確保が発生しない
 *
 * 代償として解析スレッドが1回あたり数十ms(EfficientDet-Lite0 int8でPixel 6 CPU実測29ms)
 * ブロックするが、[DayMvpSettings.phoneDetectionIntervalMs](既定500ms=2fps)に間引くため
 * 前面カメラ経路のfpsへの影響は限定的である。**実際の影響は実機で測って確認すること**。
 *
 * ## 本実装との違い
 * 診断段階では「頭部が下を向いている間だけ起動」という条件付き実行を**しない**。
 * 前を向いているときのデータが取れないと誤検知率を測れないため、間引きつつ常時走らせる。
 * 本実装では条件付き実行に切り替えて負荷をさらに下げる。
 */
class PhoneDetector(
    context: Context,
    @Volatile var settings: DayMvpSettings,
) {
    private val detector: ObjectDetector? = try {
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET_PATH).build())
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(settings.phoneDetectionScoreThreshold)
                // 対象は`cell phone`だけに絞る。飲食物等ほかのCOCOラベルは今回の計測対象外。
                .setCategoryAllowlist(listOf(PHONE_LABEL))
                .build(),
        )
    } catch (t: Throwable) {
        // モデルが同梱されていない(release build)等。診断機能なので、失敗しても本経路は止めない。
        Log.w(TAG, "ObjectDetector unavailable; phone diagnostics disabled", t)
        null
    }

    val isAvailable: Boolean get() = detector != null

    private var lastRunMs = 0L

    /**
     * 間引き間隔を過ぎていれば検出を実行する。まだなら`null`を返す(呼び出し側は前回値を使わない)。
     *
     * @param bitmap Face Landmarkerへ渡したものと同じ、回転・鏡像処理済みのBitmap。
     */
    fun detectIfDue(bitmap: Bitmap, nowMs: Long): PhoneObservation? {
        val d = detector ?: return null
        if (nowMs - lastRunMs < settings.phoneDetectionIntervalMs) return null
        lastRunMs = nowMs

        return try {
            val result = d.detect(BitmapImageBuilder(bitmap).build())
            val imageArea = (bitmap.width.toFloat() * bitmap.height.toFloat()).takeIf { it > 0f }

            // categoryAllowlistで絞っているが、念のためラベルも確認する。
            var bestScore: Float? = null
            var bestAreaRatio: Float? = null
            for (detection in result.detections()) {
                val category = detection.categories()
                    .firstOrNull { it.categoryName() == PHONE_LABEL } ?: continue
                if (bestScore != null && category.score() <= bestScore) continue
                bestScore = category.score()
                bestAreaRatio = imageArea?.let { detection.boundingBox().width() * detection.boundingBox().height() / it }
            }

            PhoneObservation(
                detected = bestScore != null,
                score = bestScore,
                areaRatio = bestAreaRatio,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "phone detection failed", t)
            null
        }
    }

    fun close() {
        detector?.close()
    }

    private companion object {
        const val TAG = "PhoneDetector"
        const val MODEL_ASSET_PATH = "efficientdet_lite0.tflite"

        /** `efficientdet_lite0.tflite`同梱のlabels.txtにおけるindex 76のラベル名。 */
        const val PHONE_LABEL = "cell phone"
    }
}
