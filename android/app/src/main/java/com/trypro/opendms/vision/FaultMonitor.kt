package com.trypro.opendms.vision

import com.trypro.opendms.domain.DayMvpSettings

/** 検知した故障の種類。UIの表示文言と診断ログに使う。 */
enum class FaultReason {
    /** CameraXのバインドに失敗した(カメラを開けない)。 */
    CAMERA_UNAVAILABLE,

    /** MediaPipeの推論エラーが連続している。 */
    INFERENCE_FAILING,

    /** フレームは投入できているのに、推論結果が返ってこない。 */
    NO_RESULT,

    /** カメラからフレームが1枚も届かない(解析が完全に停止している)。 */
    NO_FRAME,
}

/**
 * 監視システムの故障を検知する。
 *
 * ## 根拠(一次情報・必須要件)
 * 国交省ASV「ドライバーモニタリング(眠気・居眠り検知)システム 基本設計書」4章および
 * 「(脇見等検知)システム 基本設計書」4章は、いずれも
 * 「**本システムが故障を検知した場合には、故障していることをドライバーが認識できる手段を
 * 有していること**」を必須要件として定めている。
 *
 * ## 「監視不能」との違い
 * [com.trypro.opendms.domain.MonitoringAvailability.UNAVAILABLE](「顔を確認できません」)は
 * **システムは正常に動いているが判定に足る信号が得られない**状態(顔が写っていない、暗い等)である。
 * 一方この故障は**システム自体が動いていない**状態を指す。ドライバーが取るべき行動が異なる
 * (前者は取付位置・姿勢の調整、後者はアプリの再起動や端末の交換)ため、明確に分けて報知する。
 *
 * ## 判定条件
 * | 種類 | 条件 |
 * |---|---|
 * | [FaultReason.CAMERA_UNAVAILABLE] | CameraXの`bindToLifecycle`が失敗した(1回で確定) |
 * | [FaultReason.INFERENCE_FAILING] | 推論エラーが[DayMvpSettings.faultConsecutiveInferenceErrors]回連続 |
 * | [FaultReason.NO_RESULT] | フレームを投入しているのに[DayMvpSettings.faultNoResultTimeoutMs]の間結果が返らない |
 * | [FaultReason.NO_FRAME] | カメラからフレームが同時間届かない |
 *
 * ただし**起動中(まだ1枚も届いていない)だけは**[DayMvpSettings.faultStartupTimeoutMs](既定10秒)まで
 * 待つ。`bindToLifecycle`が成功してもカメラの実起動とMediaPipeモデルのロードに数秒かかるためで、
 * ここを定常時と同じ3秒にすると毎回の起動で故障を誤検知する(2026-08-31に実機で確認)。
 *
 * 推論エラーを1回で故障扱いしないのは、MediaPipeの`errorListener`が単発フレームの失敗でも
 * 呼ばれるため。無応答の判定時間(既定3秒)を「監視不能」(既定5秒)より短くしているのは、
 * 両者が同時に成立して紛らわしくなるのを避けるためである。
 *
 * ## 呼び出し方
 * 状態の記録([onCameraBindFailed]等)はイベント発生時に、[evaluate]は**フレームの到着とは
 * 独立した定期tick**から呼ぶこと。フレームが1枚も来なくなるケース([FaultReason.NO_FRAME])は、
 * フレームコールバックの中から評価したのでは永久に検知できないため。
 *
 * スレッド安全性: [onResult]/[onInferenceError]はMediaPipeのコールバックスレッド、
 * [onFrameSubmitted]はCameraXの解析スレッド、[evaluate]はUIのtickから呼ばれ得るため、
 * 状態は`@Volatile`で保持する。個々の更新は独立した単純代入なので、これで十分。
 */
class FaultMonitor(initialSettings: DayMvpSettings) {

    /** 判定に使う閾値。設定画面から変更できるよう、他のクラスと同じく生成後も更新できる。 */
    @Volatile
    var settings: DayMvpSettings = initialSettings

    @Volatile
    private var cameraBindFailed = false

    @Volatile
    private var consecutiveInferenceErrors = 0

    @Volatile
    private var lastFrameSubmittedMs: Long? = null

    @Volatile
    private var lastResultMs: Long? = null

    /**
     * 判定の起点。カメラのバインド成功時刻、またはそれより前に[reset]した時刻。
     * 起動直後の猶予を与えるために保持する。
     */
    @Volatile
    private var startedMs: Long? = null

    /** CameraXの`bindToLifecycle`が失敗した。 */
    fun onCameraBindFailed() {
        cameraBindFailed = true
    }

    /** カメラのバインドに成功した。以後の故障判定の起点にする。 */
    fun onCameraBound(nowMs: Long) {
        cameraBindFailed = false
        startedMs = nowMs
        lastFrameSubmittedMs = null
        lastResultMs = null
        consecutiveInferenceErrors = 0
    }

    /** MediaPipeへフレームを投入した。 */
    fun onFrameSubmitted(nowMs: Long) {
        lastFrameSubmittedMs = nowMs
    }

    /** 推論結果を受け取った。連続エラー数もここでリセットする。 */
    fun onResult(nowMs: Long) {
        lastResultMs = nowMs
        consecutiveInferenceErrors = 0
    }

    /** MediaPipeの`errorListener`が呼ばれた。 */
    fun onInferenceError() {
        consecutiveInferenceErrors++
    }

    /**
     * 現時点で故障とみなすか。故障でなければnull。
     *
     * @param nowMs 単調増加する現在時刻(ms)。[onFrameSubmitted]等へ渡すものと同じ時計を使うこと。
     */
    fun evaluate(nowMs: Long): FaultReason? {
        if (cameraBindFailed) return FaultReason.CAMERA_UNAVAILABLE
        if (consecutiveInferenceErrors >= settings.faultConsecutiveInferenceErrors) {
            return FaultReason.INFERENCE_FAILING
        }

        val started = startedMs ?: return null
        val timeout = settings.faultNoResultTimeoutMs
        val startupTimeout = settings.faultStartupTimeoutMs

        // 起動中(まだ1枚も届いていない)と、動いていたものが止まった場合とで待ち時間を分ける。
        // bindToLifecycleが成功してもカメラの実起動とモデルのロードに数秒かかるため、
        // 起動中に定常時と同じ3秒を適用すると毎回の起動で故障を誤検知する(2026-08-31実機確認)。
        val lastFrame = lastFrameSubmittedMs
        if (lastFrame == null) {
            return if (nowMs - started >= startupTimeout) FaultReason.NO_FRAME else null
        }
        if (nowMs - lastFrame >= timeout) return FaultReason.NO_FRAME

        val lastResult = lastResultMs
        if (lastResult == null) {
            return if (nowMs - started >= startupTimeout) FaultReason.NO_RESULT else null
        }
        if (nowMs - lastResult >= timeout) return FaultReason.NO_RESULT

        return null
    }

    /**
     * 状態を捨てて、[nowMs]を起点に判定し直す。
     *
     * **起点をnullに戻してはいけない。** nullに戻すと、その後カメラが一度もバインド
     * されないとき[evaluate]が永久にnullを返し、故障が報知されない。
     * 起点を残せば、起動猶予を過ぎた時点で[FaultReason.NO_FRAME]になる。
     */
    fun reset(nowMs: Long) {
        cameraBindFailed = false
        consecutiveInferenceErrors = 0
        lastFrameSubmittedMs = null
        lastResultMs = null
        startedMs = nowMs
    }
}
