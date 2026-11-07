package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 発報に添える位置の規則(docs/16 §1-1)。
 *
 * **地図に打つ以上、間違った点を残さないことが最優先。**
 * `SpeedProvider`は最後の測位を保持し続けるので、GPSが切れた区間では数分前の座標が残る
 * (トンネル・高架下で確実に起きる)。それをそのまま添えると、
 * **その場所で起きていないことを地図に記録する**ことになる。
 */
class EventLocationTest {

    private val now = 1_000_000L
    private val maxAge = 30_000L

    private fun at(fixedAtUtcMs: Long?, enabled: Boolean = true) = EventLocation.from(
        enabled = enabled,
        latitude = 35.681236,
        longitude = 139.767125,
        accuracyM = 8f,
        fixedAtUtcMs = fixedAtUtcMs,
        provider = "gps",
        nowUtcMs = now,
        maxAgeMs = maxAge,
    )

    @Test
    fun `新しい測位はそのまま添える`() {
        val loc = at(now - 5_000L)
        assertThat(loc.latitude).isNotNull()
        assertThat(loc.accuracyM).isNotNull()
        assertThat(loc.fixedAtUtcMs).isEqualTo(now - 5_000L)
        assertThat(loc.provider).isEqualTo("gps")
    }

    /** 境界。ちょうど上限は許す。 */
    @Test
    fun `上限ちょうどの古さは許す`() {
        assertThat(at(now - maxAge).latitude).isNotNull()
    }

    /** **これが本題。**古い測位は捨てる。嘘の座標を残すより無いほうがよい。 */
    @Test
    fun `古すぎる測位は捨てる`() {
        assertThat(at(now - maxAge - 1L)).isEqualTo(EventLocation.NONE)
        assertThat(at(now - 300_000L)).isEqualTo(EventLocation.NONE) // 5分前
    }

    /**
     * 測位時刻が分からないものも捨てる。
     * 「いつの座標か」が言えないものは地図に打てない。
     */
    @Test
    fun `測位時刻が無いものは捨てる`() {
        assertThat(at(null)).isEqualTo(EventLocation.NONE)
    }

    /** 端末の時計がずれて未来の測位に見えるものも信用しない。 */
    @Test
    fun `未来の測位は捨てる`() {
        assertThat(at(now + 1_000L)).isEqualTo(EventLocation.NONE)
    }

    /** 設定がOFFなら、測位が新しくても何も添えない。既定はOFF。 */
    @Test
    fun `設定がOFFなら添えない`() {
        assertThat(at(now, enabled = false)).isEqualTo(EventLocation.NONE)
        assertThat(DayMvpSettings.DAY_MVP0_DEFAULT.recordLocationOnEvent).isFalse()
    }

    /** 緯度経度そのものが無ければ、当然何も添えない。 */
    @Test
    fun `座標が無ければ添えない`() {
        val loc = EventLocation.from(
            enabled = true,
            latitude = null,
            longitude = null,
            accuracyM = null,
            fixedAtUtcMs = now,
            provider = null,
            nowUtcMs = now,
            maxAgeMs = maxAge,
        )
        assertThat(loc).isEqualTo(EventLocation.NONE)
    }
}
