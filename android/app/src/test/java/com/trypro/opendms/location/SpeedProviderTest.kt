package com.trypro.opendms.location

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** 公開前レビュー R-02: 測位が止まっても古い速度が残り、脇見と警告の開始を止め続けていた。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeedProviderTest {

    private val app = RuntimeEnvironment.getApplication()
    private val locationManager = app.getSystemService(LocationManager::class.java)
    private lateinit var provider: SpeedProvider

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        provider = SpeedProvider(app)
        assertThat(provider.start()).isTrue()
    }

    private fun fix(speedMps: Float) {
        shadowOf(locationManager).simulateLocation(
            Location(LocationManager.GPS_PROVIDER).apply {
                speed = speedMps
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `速度付きの測位を受けると速度が出る`() {
        fix(10f)
        assertThat(provider.speedKph.value).isWithin(0.01f).of(36f)
    }

    @Test
    fun `測位が3秒途絶えたら速度不明になる`() {
        fix(0f)
        advance(2_900L)
        assertThat(provider.speedKph.value).isEqualTo(0f)
        advance(100L)
        assertThat(provider.speedKph.value).isNull()
    }

    @Test
    fun `測位が続く間は速度を保つ`() {
        repeat(10) {
            fix(5f)
            advance(1_000L)
        }
        assertThat(provider.speedKph.value).isWithin(0.01f).of(18f)
    }

    @Test
    fun `位置情報がオフにされたらすぐ速度不明になる`() {
        fix(0f)
        shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(provider.speedKph.value).isNull()
    }
}
