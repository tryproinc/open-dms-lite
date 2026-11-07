package com.trypro.opendms.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 判定に使えないときに出す、原因と直し方(docs/19 §5、2026-10-17)。 */
class QualityAdviceTest {

    private fun quality(vararg flags: QualityFlag, valid: Boolean = false) =
        SignalQuality(score = if (valid) 1f else 0f, flags = flags.toSet(), isValidForDecision = valid)

    @Test
    fun `判定に使えるときは何も出さない`() {
        assertThat(QualityAdvice.of(quality(valid = true), faceSizeRatio = 0.3f, fps = 20f, zoomAvailable = true)).isNull()
    }

    @Test
    fun `顔が見つからないときはそれを最優先で出す`() {
        val a = QualityAdvice.of(quality(QualityFlag.NO_FACE, QualityFlag.LOW_FPS), null, 5f, zoomAvailable = true)
        assertThat(a).contains("顔が見つかりません")
    }

    @Test
    fun `顔が小さいときは大きさと直し方を出しズームが使えればズームを案内する`() {
        val withZoom = QualityAdvice.of(quality(QualityFlag.FACE_TOO_SMALL), 0.18f, 20f, zoomAvailable = true)
        val noZoom = QualityAdvice.of(quality(QualityFlag.FACE_TOO_SMALL), 0.18f, 20f, zoomAvailable = false)

        assertThat(withZoom).contains("18%")
        assertThat(withZoom).contains("ズーム")
        assertThat(noZoom).doesNotContain("ズーム")
    }

    @Test
    fun `処理が遅いときはfpsと直し方を出す`() {
        val a = QualityAdvice.of(quality(QualityFlag.LOW_FPS), 0.3f, 7.6f, zoomAvailable = true)
        assertThat(a).contains("処理が遅い")
        assertThat(a).contains("7fps")
    }

    @Test
    fun `暗いときと近すぎるときも出す`() {
        assertThat(QualityAdvice.of(quality(QualityFlag.LOW_LIGHT), 0.3f, 20f, true)).contains("暗い")
        assertThat(QualityAdvice.of(quality(QualityFlag.FACE_TOO_LARGE), 0.7f, 20f, true)).contains("近すぎ")
    }
}
