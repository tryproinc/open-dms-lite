package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * docs/17 §2-1: 表示モードは運行中(MONITORING)だけに効き、それ以外では常に映像を出す。
 * 取付位置の調整と較正には映像が要るため、ここが崩れると夜間設定のまま較正できなくなる。
 */
class MonitoringDisplayModeTest {

    @Test
    fun `運行中以外では設定に関係なく映像`() {
        MonitoringDisplayMode.entries.forEach { selected ->
            assertThat(MonitoringDisplayMode.effective(selected, monitoring = false))
                .isEqualTo(MonitoringDisplayMode.CAMERA)
        }
    }

    @Test
    fun `運行中は設定どおり`() {
        MonitoringDisplayMode.entries.forEach { selected ->
            assertThat(MonitoringDisplayMode.effective(selected, monitoring = true)).isEqualTo(selected)
        }
    }

    @Test
    fun `切替ボタンは3つを巡回して元へ戻る`() {
        assertThat(MonitoringDisplayMode.CAMERA.next()).isEqualTo(MonitoringDisplayMode.FACE_OVERLAY)
        assertThat(MonitoringDisplayMode.FACE_OVERLAY.next()).isEqualTo(MonitoringDisplayMode.INFO_ONLY)
        assertThat(MonitoringDisplayMode.INFO_ONLY.next()).isEqualTo(MonitoringDisplayMode.CAMERA)
    }

    @Test
    fun `既定は従来どおりの映像`() {
        assertThat(DayMvpSettings.DAY_MVP0_DEFAULT.monitoringDisplayMode)
            .isEqualTo(MonitoringDisplayMode.CAMERA)
    }
}
