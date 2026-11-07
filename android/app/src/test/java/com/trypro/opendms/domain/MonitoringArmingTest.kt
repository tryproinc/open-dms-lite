package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 一度 20km/h を超えるまで警告を出さない(docs/19 §3-2a)。 */
class MonitoringArmingTest {

    private val settings = DayMvpSettings.DAY_MVP0_DEFAULT

    private fun arming(startMs: Long = 0L) = MonitoringArming(settings, startMs)

    @Test
    fun `20kmに達するまでは警告を出さず達したら以後ずっと出す`() {
        val a = arming()
        assertThat(a.update(1_000L, speedKph = 0f, locationPermitted = true)).isFalse()
        assertThat(a.update(2_000L, speedKph = settings.armingSpeedKph - 0.1f, locationPermitted = true)).isFalse()
        assertThat(a.update(3_000L, speedKph = settings.armingSpeedKph, locationPermitted = true)).isTrue()
        // 止まっても止めない。
        assertThat(a.update(4_000L, speedKph = 0f, locationPermitted = true)).isTrue()
        assertThat(a.update(5_000L, speedKph = null, locationPermitted = true)).isTrue()
    }

    @Test
    fun `位置情報が未許可なら最初から出す`() {
        assertThat(arming().update(0L, speedKph = null, locationPermitted = false)).isTrue()
    }

    @Test
    fun `動作確認モードなら最初から出す`() {
        val a = MonitoringArming(settings.copy(testModeIgnoreSpeedGate = true), 0L)
        assertThat(a.update(0L, speedKph = 0f, locationPermitted = true)).isTrue()
    }

    @Test
    fun `許可済みでも速度が一度も取れないまま2分たったら出す`() {
        val a = arming(startMs = 10_000L)
        assertThat(a.update(10_000L + settings.armingNoSpeedFallbackMs - 1, null, locationPermitted = true)).isFalse()
        assertThat(a.update(10_000L + settings.armingNoSpeedFallbackMs, null, locationPermitted = true)).isTrue()
    }

    @Test
    fun `速度が取れ続けていれば2分たっても20kmまで待つ`() {
        val a = arming()
        var t = 0L
        while (t <= settings.armingNoSpeedFallbackMs * 2) {
            assertThat(a.update(t, speedKph = 5f, locationPermitted = true)).isFalse()
            t += 1_000L
        }
    }

    /** 公開前レビュー R-02: 0km/h を取った後に測位が止まると、警告が永久に始まらなかった。 */
    @Test
    fun `速度が取れた後に途絶えたら途絶えてから2分で出す`() {
        val a = arming()
        a.update(1_000L, speedKph = 0f, locationPermitted = true)
        assertThat(a.update(1_500L, speedKph = null, locationPermitted = true)).isFalse()
        assertThat(a.update(1_000L + settings.armingNoSpeedFallbackMs - 1, null, locationPermitted = true)).isFalse()
        assertThat(a.update(1_000L + settings.armingNoSpeedFallbackMs, null, locationPermitted = true)).isTrue()
    }

    @Test
    fun `途絶えても2分以内に速度が戻れば待ち直す`() {
        val a = arming()
        a.update(0L, speedKph = 0f, locationPermitted = true)
        a.update(settings.armingNoSpeedFallbackMs - 1, null, locationPermitted = true)
        assertThat(a.update(settings.armingNoSpeedFallbackMs, 3f, locationPermitted = true)).isFalse()
        assertThat(a.update(settings.armingNoSpeedFallbackMs * 2 - 1, null, locationPermitted = true)).isFalse()
    }
}
