package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Test

/** 閾値設定画面に出す10項目の定義([TunableSettings])。 */
class TunableSettingsTest {

    private val d = DayMvpSettings.DAY_MVP0_DEFAULT
    private val items = TunableSettings.items

    @Test
    fun `項目は10個で、idが重複しない`() {
        assertThat(items).hasSize(10)
        assertThat(items.map { it.id }.toSet()).hasSize(10)
    }

    @Test
    fun `どの項目にも名前と説明と向きの表示がある`() {
        items.forEach {
            assertThat(it.label).isNotEmpty()
            assertThat(it.description).isNotEmpty()
            assertThat(it.lowLabel).isNotEmpty()
            assertThat(it.highLabel).isNotEmpty()
            assertThat(it.group).isNotEmpty()
        }
    }

    @Test
    fun `既定値は範囲内で、刻みの上にある`() {
        items.forEach {
            val v = it.get(d)
            assertThat(v).isAtLeast(it.range.start)
            assertThat(v).isAtMost(it.range.endInclusive)
            val steps = (v - it.range.start) / it.step
            assertThat(abs(steps - steps.roundToInt())).isLessThan(1e-3f)
        }
    }

    @Test
    fun `既定値を設定しても設定は変わらない`() {
        items.forEach { assertThat(it.set(d, it.get(d))).isEqualTo(d) }
    }

    @Test
    fun `設定した値が読み戻せる`() {
        items.forEach {
            val v = it.range.endInclusive
            assertThat(it.get(it.set(d, v))).isWithin(1e-3f).of(v)
        }
    }

    @Test
    fun `各項目は対応する設定だけを変える`() {
        val byId = items.associateBy { it.id }
        val changed = byId.getValue("eyeClosureCaution").set(d, 3.0f)
        assertThat(changed.eyeClosureCautionMs).isEqualTo(3_000L)
        assertThat(changed.copy(eyeClosureCautionMs = d.eyeClosureCautionMs)).isEqualTo(d)

        assertThat(byId.getValue("yawnWindow").set(d, 30f).yawnWindowMs).isEqualTo(1_800_000L)
        assertThat(byId.getValue("distractionWarning").set(d, 2.5f).distractionWarningMs).isEqualTo(2_500L)
        assertThat(byId.getValue("alertCooldown").set(d, 20f).alertCooldownMs).isEqualTo(20_000L)
        assertThat(byId.getValue("noddingReversals").set(d, 7f).noddingMinReversals).isEqualTo(7)
        assertThat(byId.getValue("yawnCount").set(d, 2f).yawnMinCountInWindow).isEqualTo(2)
        assertThat(byId.getValue("distractionYaw").set(d, 30f).distractionYawDeg).isEqualTo(30f)
        assertThat(byId.getValue("distractionPitch").set(d, 15f).distractionPitchDownDeg).isEqualTo(15f)
        assertThat(byId.getValue("distractionMinSpeed").set(d, 20f).distractionMinSpeedKph).isEqualTo(20f)
        assertThat(byId.getValue("eyeClosureWarning").set(d, 4f).eyeClosureWarningMs).isEqualTo(4_000L)
    }

    @Test
    fun `既定値のままなら整合を取っても変わらない`() {
        assertThat(TunableSettings.normalize(d)).isEqualTo(d)
    }

    @Test
    fun `強い警告は注意より500ms以上長くなる`() {
        val s = d.copy(eyeClosureCautionMs = 3_000L, eyeClosureWarningMs = 1_000L)
        assertThat(TunableSettings.normalize(s).eyeClosureWarningMs).isEqualTo(3_500L)
    }

    @Test
    fun `脇見の警告まで通常を短くしたら時速50km超の警告時間もそれ以下に揃える`() {
        val s = d.copy(distractionWarningMs = 2_000L)
        assertThat(TunableSettings.normalize(s).distractionWarningMsHighSpeed).isEqualTo(2_000L)
    }

    @Test
    fun `脇見の警告まで通常が長いときは時速50km超の警告時間を変えない`() {
        val s = d.copy(distractionWarningMs = 10_000L)
        assertThat(TunableSettings.normalize(s).distractionWarningMsHighSpeed).isEqualTo(3_500L)
    }

    @Test
    fun `範囲外の値は範囲と刻みに丸める`() {
        val item = items.first { it.id == "distractionWarning" }
        assertThat(TunableSettings.snap(item, 99f)).isEqualTo(10f)
        assertThat(TunableSettings.snap(item, 0f)).isEqualTo(2f)
        assertThat(TunableSettings.snap(item, 6.2f)).isEqualTo(6.0f)
        assertThat(TunableSettings.snap(item, 6.3f)).isEqualTo(6.5f)
    }
}
