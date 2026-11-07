package com.trypro.opendms.vision

/**
 * スマホの物理的な取付回転角度(0/90/180/270)に関する純粋関数群。
 *
 * ## 背景
 * `AndroidManifest.xml`は`screenOrientation="portrait"`に固定しているため、スマホを
 * 上下逆さま・横向きに設置しても`Display.getRotation()`やCameraXの`targetRotation`は
 * 変化しない。したがって`imageProxy.imageInfo.rotationDegrees`は常に「通常のポートレート
 * 設置」を前提にした固定値のままになり、実際の取付向きを反映しない
 * (2026-08-29のこっくり/軸割り当て調査と同種の「一見動いているが向きの前提が違う」問題)。
 *
 * これを補うため、`android.view.OrientationEventListener`(画面ロックと無関係に加速度センサーで
 * 実際の物理回転を検知できる)の値を「取付回転角」として別途取得し、カメラ画像の補正回転角へ
 * 合成する。
 *
 * ## 一次情報(2026-08-29 developer.android.com で確認)
 * - `OrientationEventListener#onOrientationChanged`: 「0=自然な向き、90=左側が上、
 *   180=上下逆、270=右側が上」。度数は自然な向きから**時計回り**に測る。
 * - `androidx.camera.core.ImageInfo#getRotationDegrees`: 「画像を正しい向きにするために
 *   バッファへ適用すべき**時計回り**の回転角」。
 *
 * 両者とも「時計回り正」の規約で一致している。ただし**前面カメラは端末の回転と逆向きに補正する**
 * (`sensor - θ`。[frontAnalysisRotation])。2026-10-15まで後ろのカメラの式(`sensor + θ`)を使っており、
 * 90°/270°で解析画像の顔が上下逆になっていた。**符号は実機の4方向(0/90/180/270)全てで見え方を確認すること**
 * (このプロジェクトで頭部姿勢オイラー角の軸割り当てを取り違えていた前例があるため)。
 */
object MountRotation {

    /** 有効な取付回転角の集合。 */
    val VALID_DEGREES = setOf(0, 90, 180, 270)

    /**
     * [android.view.OrientationEventListener]の生角度(0-359、検知不能なら負数=ORIENTATION_UNKNOWN)を
     * 90°刻みへ量子化する。検知不能ならnullを返す。
     */
    fun quantizeTo90(rawDegrees: Int): Int? {
        if (rawDegrees < 0) return null
        val normalized = normalize(rawDegrees)
        return normalize((((normalized + 45) / 90) % 4) * 90)
    }

    /**
     * インカメラの解析画像を正立させる回転角(時計回り、0-359)。端末を時計回りに[mountRotationDegrees]付けたとき。
     *
     * CameraXの前面カメラの回転は `sensor + 画面の回転`(`CameraOrientationUtil.getRelativeImageRotation`)。
     * 画面は縦固定なので[cameraRotationDegrees]は常に`sensor`だが、端末を時計回りに θ 付けると、
     * 画面が追従していれば画面の回転は `360 - θ` になる。よって `sensor - θ`。
     *
     * **2026-10-15まで`sensor + θ`(後ろのカメラの式)を使っていた。** 180°は同じ値になるが、
     * 90°/270°では解析画像の顔が上下逆になり、オーバーレイも顔と逆向きに出ていた(利用者の報告)。
     * [com.trypro.opendms.vision.MountOrientationUprightTest]で固定している。
     */
    fun frontAnalysisRotation(cameraRotationDegrees: Int, mountRotationDegrees: Int): Int =
        normalize(cameraRotationDegrees - mountRotationDegrees)

    /** 回転の結果、幅と高さが入れ替わる(90°または270°)かどうか。 */
    fun swapsDimensions(totalRotationDegrees: Int): Boolean {
        val n = normalize(totalRotationDegrees)
        return n == 90 || n == 270
    }

    /**
     * 取付回転だけを施した正規化座標(nx, ny)を、その回転前(取付回転を知らない生のカメラ映像)の
     * 正規化座標へ逆変換する。デバッグオーバーレイのlandmark座標を、取付回転を知らずに描画される
     * `PreviewView`の座標系へ合わせるために使う。
     *
     * カメラ本体の回転・前面カメラのミラーはこの逆変換に含めない
     * (`PreviewView`側が取付回転と無関係に別途行っており、[MediaPipeFaceAnalyzer]が
     * MediaPipeへ渡す画像に適用する回転量は「カメラ本体の回転+取付回転」の合成値だが、
     * `PreviewView`とのズレは取付回転の分だけのため)。
     *
     * 導出(単位正方形を時計回りにθ回転させたときの逆変換。90°/270°は
     * [MountRotationTest]で四隅の移動先を具体的に検算して確認済み):
     * - 0°: 恒等変換
     * - 90°: raw = (ny, 1-nx)
     * - 180°: raw = (1-nx, 1-ny)
     * - 270°: raw = (1-ny, nx)
     */
    fun undoRotation(nx: Float, ny: Float, mountRotationDegrees: Int): Pair<Float, Float> =
        when (normalize(mountRotationDegrees)) {
            90 -> ny to (1f - nx)
            180 -> (1f - nx) to (1f - ny)
            270 -> (1f - ny) to nx
            else -> nx to ny
        }

    private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
