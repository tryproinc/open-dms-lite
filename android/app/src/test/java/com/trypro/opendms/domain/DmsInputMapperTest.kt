package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import com.trypro.opendms.vision.FeatureFrame
import com.trypro.opendms.vision.SignalQuality
import org.junit.Test

class DmsInputMapperTest {

    private val settings = DayMvpSettings.DAY_MVP0_DEFAULT

    private fun frame(
        eyeLeftOpen: Float? = 0.9f,
        eyeRightOpen: Float? = 0.9f,
        jawOpen: Float? = 0f,
        headYawDeg: Float? = 0f,
        headPitchDeg: Float? = 0f,
    ) = FeatureFrame(
        monotonicTimeMs = 1000L,
        wallClockUtcMs = 0L,
        faceDetected = true,
        confidence = null,
        faceSizeRatio = 0.35f,
        eyeLeftOpen = eyeLeftOpen,
        eyeRightOpen = eyeRightOpen,
        jawOpen = jawOpen,
        headYawDeg = headYawDeg,
        headPitchDeg = headPitchDeg,
        headRollDeg = 0f,
        luminance = 120f,
        inferenceTimeMs = 50L,
        fps = 15f,
        thermalStatus = 0,
        imageWidth = 480,
        imageHeight = 640,
        landmarks = emptyList(),
        quality = SignalQuality(score = 1f, flags = emptySet(), isValidForDecision = true),
    )

    private fun calibration(
        eyeLeftBaseline: Float = 0.9f,
        eyeRightBaseline: Float = 0.9f,
        yawBaseline: Float = 0f,
        pitchBaseline: Float = 0f,
    ) = CalibrationProfile(
        baselineYawDeg = yawBaseline,
        baselinePitchDeg = pitchBaseline,
        baselineRollDeg = 0f,
        eyeOpenLeftBaseline = eyeLeftBaseline,
        eyeOpenRightBaseline = eyeRightBaseline,
        faceSizeBaseline = 0.35f,
        calibratedAtUtcMs = 0L,
        settingsVersion = settings.version,
    )

    @Test
    fun `較正済みで両目とも開眼比0_45以下ならeyesClosed=true`() {
        val f = frame(eyeLeftOpen = 0.30f, eyeRightOpen = 0.35f) // /0.9 baseline -> 0.33, 0.39 (<=0.45)
        val input = DmsInputMapper.from(f, calibration(), settings)
        assertThat(input.eyesClosed).isTrue()
    }

    /**
     * 閉眼較正があればその閾値を使う(2026-09-11)。
     * 実測: 開0.74・閉0.32(正規化0.43)。固定0.45では閉じた目の値の真上で出入りしていた。
     * 較正閾値0.60なら、正規化0.50(生値0.37)でも閉眼になる。
     */
    @Test
    fun `閉眼較正があれば設定の固定閾値ではなく較正の閾値で判定する`() {
        val f = frame(eyeLeftOpen = 0.37f, eyeRightOpen = 0.37f) // 0.37/0.74 = 0.50
        val fixed = DmsInputMapper.from(f, calibration(eyeLeftBaseline = 0.74f, eyeRightBaseline = 0.74f), settings)
        assertThat(fixed.eyesClosed).isFalse() // 固定0.45では開扱い
        val calibrated = calibration(eyeLeftBaseline = 0.74f, eyeRightBaseline = 0.74f)
            .copy(eyeClosedLeftBaseline = 0.32f, eyeClosedRightBaseline = 0.32f, eyeClosedOpenRatioThreshold = 0.60f)
        val byProfile = DmsInputMapper.from(f, calibrated, settings)
        assertThat(byProfile.eyesClosed).isTrue()
    }

    @Test
    fun `片目だけ閉じていてもeyesClosed=false`() {
        val f = frame(eyeLeftOpen = 0.30f, eyeRightOpen = 0.85f) // 右目は開眼比0.94相当
        val input = DmsInputMapper.from(f, calibration(), settings)
        assertThat(input.eyesClosed).isFalse()
    }

    /**
     * 2026-08-29実機回帰: 較正基準が左右で偏り(左0.768/右0.670)、かつMediaPipeのblendshapeが
     * 右目側でほとんど下がらない(閉眼時でも生値0.288=正規化0.43)実測ケース。
     * 旧「左右ともに0.45以下」(AND)方式ではこのとき閉眼と判定できず、
     * 10秒閉眼しても最大701msしか継続時間が伸びず警告が出なかった(診断ログ
     * session_8c2033fe で確認)。左右平均方式ではこのケースを閉眼として判定できる。
     */
    @Test
    fun `左右の較正基準と感度に偏りがある実測ケースでもeyesClosed=trueになる`() {
        val f = frame(eyeLeftOpen = 0.258f, eyeRightOpen = 0.288f)
        val input = DmsInputMapper.from(
            f,
            calibration(eyeLeftBaseline = 0.7682f, eyeRightBaseline = 0.6699f),
            settings,
        )
        // 左=0.336, 右=0.430 → 右単独では0.45を下回らずAND方式では検知できないが、平均0.383で成立する。
        assertThat(input.eyesClosed).isTrue()
    }

    /** 片目が完全に閉じても、もう片方が開眼基準どおり開いていれば平均方式でも閉眼としない。 */
    @Test
    fun `片目が完全閉眼でももう片方が開いていればeyesClosed=false`() {
        val f = frame(eyeLeftOpen = 0f, eyeRightOpen = 0.9f)
        val input = DmsInputMapper.from(f, calibration(), settings)
        assertThat(input.eyesClosed).isFalse()
    }

    @Test
    fun `未較正(calibration=null)の間はeyesClosedを常にfalseとする`() {
        val f = frame(eyeLeftOpen = 0.01f, eyeRightOpen = 0.01f) // 生値としては閉眼寄り
        val input = DmsInputMapper.from(f, calibration = null, settings = settings)
        assertThat(input.eyesClosed).isFalse()
    }

    @Test
    fun `較正基準に対する相対yawが閾値を超えるとdistracted=true`() {
        val f = frame(headYawDeg = 40f)
        val input = DmsInputMapper.from(f, calibration(yawBaseline = 10f), settings) // 相対30度
        assertThat(input.distracted).isTrue()
    }

    @Test
    fun `未較正でも絶対角度がしきい値を超えればdistracted=trueになる(フォールバック)`() {
        val f = frame(headYawDeg = 40f)
        val input = DmsInputMapper.from(f, calibration = null, settings = settings)
        assertThat(input.distracted).isTrue()
    }

    @Test
    fun `品質評価をそのままvalidForDecisionへ引き継ぐ`() {
        val f = frame().copy(quality = SignalQuality(score = 0f, flags = setOf(), isValidForDecision = false))
        val input = DmsInputMapper.from(f, calibration(), settings)
        assertThat(input.validForDecision).isFalse()
    }

    /**
     * 2026-09-27机上試験: 横を向いている最中に、較正時の6割ほどの大きさで「正面」と出る誤検出が
     * 判定に使われ、監視不能の計測を戻していた。
     */
    @Test
    fun `較正時より大きく縮んだ顔は判定に使わない`() {
        val cal = calibration()
        val limit = cal.faceSizeBaseline * settings.faceSizeSuspectRatio

        val shrunk = DmsInputMapper.from(frame().copy(faceSizeRatio = limit - 0.01f), cal, settings)
        val normal = DmsInputMapper.from(frame().copy(faceSizeRatio = limit + 0.01f), cal, settings)

        assertThat(shrunk.validForDecision).isFalse()
        assertThat(normal.validForDecision).isTrue()
    }

    @Test
    fun `未較正なら顔サイズでは判定から外さない`() {
        val input = DmsInputMapper.from(frame().copy(faceSizeRatio = 0.05f), null, settings)

        assertThat(input.validForDecision).isTrue()
    }
}
