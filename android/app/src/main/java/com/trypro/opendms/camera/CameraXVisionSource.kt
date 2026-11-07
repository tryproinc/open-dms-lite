package com.trypro.opendms.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 前面カメラのPreviewとImageAnalysisをCameraXで取得する。
 *
 * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.2:
 * - 前面カメラ、縦向き、640x480相当、STRATEGY_KEEP_ONLY_LATESTで解析待ちを蓄積しない。
 * - ImageProxyは成功・例外・キャンセルの全経路で必ずcloseする。
 *   (`onFrame`がどのような結果になっても、このクラスがtry/finallyでcloseを保証する。
 *   呼び出し側はcloseを意識しなくてよい。)
 *
 * D0-002時点では`onFrame`はまだMediaPipeへ渡さない(D0-003で置き換える)。フレームや
 * bitmapをここで保存・キャッシュしないこと。
 */
class CameraXVisionSource(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null

    /** インカメラのズーム。バインド前に設定されたら、バインド直後に掛ける。 */
    private var zoomRatio = 1.0f

    /** ズームを掛ける。端末の範囲外は端末の範囲に収める。メインスレッドから呼ぶ。 */
    fun setZoomRatio(ratio: Float) {
        zoomRatio = ratio
        val cam = camera ?: return
        val range = cam.cameraInfo.zoomState.value
        val clamped = if (range != null) ratio.coerceIn(range.minZoomRatio, range.maxZoomRatio) else ratio
        runCatching { cam.cameraControl.setZoomRatio(clamped) }
            .onFailure { Log.w(TAG, "setZoomRatio failed", it) }
    }
    private var analysisExecutor: ExecutorService? = null

    /**
     * @param onBindResult バインドの成否を通知する。ASV眠気4章/脇見4章の故障告知のため、
     *   カメラを開けなかったことを[com.trypro.opendms.vision.FaultMonitor]へ伝えるのに使う。
     *   メインスレッドから呼ばれる。
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFrame: (ImageProxy) -> Unit,
        onBindResult: (Boolean) -> Unit = {},
    ) {
        val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            TARGET_RESOLUTION,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // MediaPipe側(MediaPipeFaceAnalyzer)がimageProxy.planes[0].bufferを
                    // 単一プレームとしてそのままBitmapへコピーする実装のため、RGBA_8888を明示する。
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor) { imageProxy ->
                            try {
                                onFrame(imageProxy)
                            } catch (t: Throwable) {
                                Log.e(TAG, "onFrame failed", t)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }

                try {
                    provider.unbindAll()
                    this.camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                    setZoomRatio(zoomRatio)
                    onBindResult(true)
                } catch (e: Exception) {
                    // 従来はログのみで、カメラが開けなくてもドライバーには何も伝わらなかった。
                    // ASV眠気4章/脇見4章の故障告知のため、呼び出し側へ失敗を通知する。
                    Log.e(TAG, "bindToLifecycle failed", e)
                    onBindResult(false)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** カメラを解放する。Composeの`onDispose`やActivity終了時に必ず呼ぶ。 */
    fun shutdown() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }

    private companion object {
        const val TAG = "CameraXVisionSource"
        val TARGET_RESOLUTION = Size(640, 480)
    }
}
