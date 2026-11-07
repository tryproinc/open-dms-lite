package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 2026-08-29回帰: 較正値・保存設定の「意味」が変わったversionでは、保存済みデータを
 * 破棄して再較正/既定値へ戻す必要がある。
 *
 * 経緯: 頭部角度の軸割り当て(yaw/pitch/rollが1つずつずれていた)を修正した際、
 * 旧軸で測定された較正値がそのまま使われ続け、相対角度に誤ったオフセットが乗って
 * 左右どちらか一方が脇見閾値に永久に到達しない状態になっていた。
 *
 * ここではversion文字列そのものを固定し、意味を変える修正時にversionを上げ忘れないようにする
 * (このテストが落ちたら、[com.trypro.opendms.data.CalibrationStore]と
 * [com.trypro.opendms.data.SettingsStore]の破棄条件が意図通り働くかを確認してから更新すること)。
 */
class CalibrationVersionTest {

    @Test
    fun `軸修正を反映したversionになっている`() {
        assertThat(DayMvpSettings.DAY_MVP0_DEFAULT.version).isEqualTo("day-mvp0-v2")
    }

    /**
     * 2026-08-31実機で発生した問題の回帰: 動作確認モードのスイッチが`save()`(全項目書き込み)を
     * 使っていたため、スイッチを1度押しただけでその時点の全閾値が端末へ固定され、以後アプリ側で
     * 既定値を改善しても届かなくなっていた。較正値の互換性(version)とは寿命が違うので、
     * 保存済み閾値の互換性は別の識別子で管理する。
     */
    @Test
    fun `較正版と設定スキーマ版は別の識別子である`() {
        val d = DayMvpSettings.DAY_MVP0_DEFAULT
        assertThat(d.settingsSchemaVersion).isNotEqualTo(d.version)
        assertThat(d.settingsSchemaVersion).startsWith("settings-")
    }

    @Test
    fun `既定値がレビュー指摘を反映した値になっている`() {
        val d = DayMvpSettings.DAY_MVP0_DEFAULT
        // 自然な首振りの実測(相対yaw -48.6〜+30.3°)を品質無効にしないための上限。
        assertThat(d.maxAbsRelativeYawDeg).isEqualTo(60f)
        // 自然な上下動(相対pitch -45.6〜+51.9°)を品質無効にしないための上限。
        assertThat(d.maxAbsRelativePitchDeg).isEqualTo(70f)
        // 閉眼の細切れを繋ぐ猶予。300msでは1300〜1450msで途切れ1500msに届かなかった。
        assertThat(d.qualityGapGraceMs).isEqualTo(500L)
        // 利用者指示によりcooldownは10秒。
        assertThat(d.alertCooldownMs).isEqualTo(10_000L)
        // 2026-08-31: 「眠気の兆候がやたら出る」との実機フィードバックを受け、
        // 端末上の全診断ログ(13セッション・約16分)のオフライン再現にもとづいて調整した値。
        // 通常の頭部の動きは平均2.0秒に1回・中央値3.5°の反転を生むため、旧値(振幅3.0°・回数3・
        // 開眼0.80)ではほぼ常時こっくり成立になっていた。一方で6.0°/0.65まで締めると
        // 本物のこっくりにも一切反応しなくなったため、両方が成立する点へ緩め直している。
        // あくび抑制の閾値は検出閾値と分ける(流用すると口が少し開くたびにこっくりが止まる)。
        assertThat(d.noddingYawnSuppressionJawOpen).isGreaterThan(d.yawnJawOpenThreshold)
        assertThat(d.noddingMinAmplitudeDeg).isEqualTo(5.0f)
        assertThat(d.noddingMinReversals).isEqualTo(5)
        assertThat(d.noddingMaxEyeOpenRatio).isEqualTo(0.75f)
    }
}
