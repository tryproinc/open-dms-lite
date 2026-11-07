package com.trypro.opendms.domain

import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 高温かどうか(docs/19 §6-6)。端末の申告の絶対値ではなく、見てきた最低値より上がったかで決める。 */
class ThermalWatchTest {

    @Test
    fun `最初からSEVEREなら高温としない`() {
        val w = ThermalWatch()
        assertThat(w.update(PowerManager.THERMAL_STATUS_SEVERE)).isFalse()
        assertThat(w.update(PowerManager.THERMAL_STATUS_SEVERE)).isFalse()
    }

    @Test
    fun `最低値より上がってSEVERE以上なら高温`() {
        val w = ThermalWatch()
        w.update(PowerManager.THERMAL_STATUS_MODERATE)
        assertThat(w.update(PowerManager.THERMAL_STATUS_SEVERE)).isTrue()
        assertThat(w.update(PowerManager.THERMAL_STATUS_MODERATE)).isFalse()
    }

    @Test
    fun `上がってもSEVERE未満なら高温としない`() {
        val w = ThermalWatch()
        w.update(PowerManager.THERMAL_STATUS_NONE)
        assertThat(w.update(PowerManager.THERMAL_STATUS_MODERATE)).isFalse()
    }
}
