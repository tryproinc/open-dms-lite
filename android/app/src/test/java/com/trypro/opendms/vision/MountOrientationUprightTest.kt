package com.trypro.opendms.vision

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 取付回転を選んだとき、**解析画像の顔が正立するか**(2026-10-15)。
 *
 * [OverlayRotationTest]は「点がプレビューの同じ場所に重なるか」だけを見ていて、
 * 顔の向き(目が口より上か)は見ていなかった。そのため、インカメラの 90°/270° で
 * **解析画像の顔が上下逆になる**誤りがすり抜けた(180°では逆回転と同じなので症状が出ない)。
 * 利用者の報告「回転を変えると、オーバーレイだけが回って映像は回らない」。
 *
 * 目 (0.5, 0.3) と口 (0.5, 0.7) の2点で顔を表し、変換したあとも目が口より上(yが小さい)で、
 * 左右にずれていないことを確かめる。
 */
class MountOrientationUprightTest {

    private val eye = 0.5f to 0.3f
    private val mouth = 0.5f to 0.7f

    /** 時計回りの回転(`FaceOverlay`の`rotateNormalized`、`View.rotation`と同じ向き)。 */
    private fun rotate(p: Pair<Float, Float>, deg: Int): Pair<Float, Float> {
        val (x, y) = p
        return when (((deg % 360) + 360) % 360) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }
    }

    private fun mirror(p: Pair<Float, Float>) = (1f - p.first) to p.second

    private fun assertUpright(e: Pair<Float, Float>, m: Pair<Float, Float>, what: String) {
        assertWithMessage("$what: 目のy").that(e.second).isLessThan(m.second)
        assertWithMessage("$what: 目と口のx").that(e.first).isWithin(1e-5f).of(m.first)
    }

    // ---- インカメラ ----

    /**
     * CameraXの前面カメラの回転は `sensor + 画面の回転` (`CameraOrientationUtil.getRelativeImageRotation`、
     * 前面は isOppositeFacing=false)。画面は縦固定なので画面の回転は常に0だが、端末を時計回りに θ 付けたとき
     * 画面が追従していれば画面の回転は `360 - θ` になる。**その値と同じになること。**
     * (後ろのカメラは `sensor - 画面の回転` = `sensor + θ`。以前はこちらの式を前面に使っていた。)
     */
    @Test
    fun `インカメラの解析の回転はCameraXが画面追従時に返す値と同じ`() {
        listOf(90, 270).forEach { sensor ->
            listOf(0, 90, 180, 270).forEach { mount ->
                val displayRotation = (360 - mount) % 360
                assertWithMessage("sensor=$sensor mount=$mount").that(MountRotation.frontAnalysisRotation(sensor, mount))
                    .isEqualTo((sensor + displayRotation) % 360)
            }
        }
    }

    /**
     * 端末を時計回りに θ 付けると、インカメラに写る世界は**時計回りに θ 回って**見える
     * (カメラが利用者の側を向いているので、端末の回転と同じ向きに回る)。
     * センサーの生の画像を、解析の回転で回して鏡像にした結果の顔が正立すること。
     */
    @Test
    fun `インカメラを時計回りに付けても解析画像の顔は正立する`() {
        val sensor = 270
        listOf(0, 90, 180, 270).forEach { mount ->
            // 世界で正立した顔 → 端末に対して θ 回って見える → センサーの生の画像(sensor分戻す)
            fun toBuffer(p: Pair<Float, Float>) = rotate(rotate(p, mount), -sensor)
            val t = MountRotation.frontAnalysisRotation(sensor, mount)
            val e = mirror(rotate(toBuffer(eye), t))
            val m = mirror(rotate(toBuffer(mouth), t))
            assertUpright(e, m, "mount=$mount")
        }
    }

    /** インカメラのオーバーレイ(取付回転を打ち消す)が、プレビュー(sensor分だけ回して鏡像)の顔に重なること。 */
    @Test
    fun `インカメラのオーバーレイはプレビューの顔に重なる`() {
        val sensor = 270
        val q = 0.35f to 0.25f // 生の画像の中の特徴点
        listOf(0, 90, 180, 270).forEach { mount ->
            val landmark = mirror(rotate(q, MountRotation.frontAnalysisRotation(sensor, mount)))
            val overlay = MountRotation.undoRotation(landmark.first, landmark.second, mount)
            val preview = mirror(rotate(q, sensor))
            assertWithMessage("mount=$mount x").that(overlay.first).isWithin(1e-5f).of(preview.first)
            assertWithMessage("mount=$mount y").that(overlay.second).isWithin(1e-5f).of(preview.second)
        }
    }
}
