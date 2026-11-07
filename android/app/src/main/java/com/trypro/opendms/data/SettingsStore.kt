package com.trypro.opendms.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trypro.opendms.domain.DayMvpSettings
import com.trypro.opendms.domain.MonitoringDisplayMode
import com.trypro.opendms.domain.TunableItem
import com.trypro.opendms.domain.TunableSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "day_mvp0_settings")

/**
 * 設定をDataStoreへ保存する。保存するのは次の2種類だけ。
 *
 * - **閾値設定画面の10項目**([TunableSettings.items])。画面の単位(秒・回・度など)のまま保存する
 * - **スイッチ類**([SWITCH_KEYS])。動作確認モード・位置の記録・運行中の表示・小窓(PiP)
 *
 * これ以外の閾値は`DayMvpSettings.DAY_MVP0_DEFAULT`の値のまま固定で、保存しない。
 * 保存が無い項目は既定値を使うので、**既定値を改善すれば全端末に届く**。
 */
class SettingsStore(private val context: Context) {

    val settingsFlow: Flow<DayMvpSettings> = context.settingsDataStore.data.map { prefs ->
        val d = DayMvpSettings.DAY_MVP0_DEFAULT
        // スイッチ類は閾値ではないので、スキーマ版に関係なく保存値を使う。
        val withSwitches = d.copy(
            testModeIgnoreSpeedGate = prefs[KEY_TEST_MODE_IGNORE_SPEED_GATE] ?: d.testModeIgnoreSpeedGate,
            recordLocationOnEvent = prefs[KEY_RECORD_LOCATION_ON_EVENT] ?: d.recordLocationOnEvent,
            monitoringDisplayMode = prefs[KEY_MONITORING_DISPLAY_MODE]
                ?.let { name -> runCatching { MonitoringDisplayMode.valueOf(name) }.getOrNull() }
                ?: d.monitoringDisplayMode,
            pictureInPictureEnabled = prefs[KEY_PICTURE_IN_PICTURE_ENABLED] ?: d.pictureInPictureEnabled,
            frontCameraZoomRatio = prefs[KEY_FRONT_CAMERA_ZOOM_RATIO] ?: d.frontCameraZoomRatio,
            locationPromptSuppressed = prefs[KEY_LOCATION_PROMPT_SUPPRESSED] ?: d.locationPromptSuppressed,
            showDiagnosticDetails = prefs[KEY_SHOW_DIAGNOSTIC_DETAILS] ?: d.showDiagnosticDetails,
        )
        // 保存時のスキーマ版が現在と違えば、閾値の保存値は使わず既定値を返す。
        if ((prefs[KEY_SCHEMA_VERSION] ?: "") != d.settingsSchemaVersion) return@map withSwitches
        TunableSettings.normalize(
            TunableSettings.items.fold(withSwitches) { acc, item ->
                prefs[key(item)]?.let { item.set(acc, TunableSettings.snap(item, it)) } ?: acc
            },
        )
    }

    /** 閾値設定画面の10項目を保存する。ほかの閾値は保存しない(既定値の改善が届かなくなるため)。 */
    suspend fun saveTunable(settings: DayMvpSettings) = editSingleSetting { prefs ->
        TunableSettings.items.forEach { prefs[key(it)] = it.get(settings) }
    }

    /** 閾値を既定値へ戻す。スイッチ類は残す。 */
    suspend fun resetTunable() = editSingleSetting { prefs ->
        TunableSettings.items.forEach { prefs.remove(key(it)) }
    }

    suspend fun setPictureInPictureEnabled(enabled: Boolean) =
        editSingleSetting { it[KEY_PICTURE_IN_PICTURE_ENABLED] = enabled }

    /** 発報時に位置を記録するかだけを保存する。運転者への周知内容が変わるスイッチ。 */
    suspend fun setRecordLocationOnEvent(enabled: Boolean) =
        editSingleSetting { it[KEY_RECORD_LOCATION_ON_EVENT] = enabled }

    /** インカメラのズームだけを保存する。 */
    suspend fun setFrontCameraZoomRatio(ratio: Float) =
        editSingleSetting { it[KEY_FRONT_CAMERA_ZOOM_RATIO] = ratio }

    /** 運行開始時の位置情報の確認を出すかだけを保存する。 */
    suspend fun setLocationPromptSuppressed(suppressed: Boolean) =
        editSingleSetting { it[KEY_LOCATION_PROMPT_SUPPRESSED] = suppressed }

    /** 画面上部に数値も出すかだけを保存する。 */
    suspend fun setShowDiagnosticDetails(enabled: Boolean) =
        editSingleSetting { it[KEY_SHOW_DIAGNOSTIC_DETAILS] = enabled }

    suspend fun setTestMode(enabled: Boolean) =
        editSingleSetting { it[KEY_TEST_MODE_IGNORE_SPEED_GATE] = enabled }

    /** 運行中の表示モードだけを保存する(docs/17)。運行中の切替ボタンからも呼ぶ。 */
    suspend fun setMonitoringDisplayMode(mode: MonitoringDisplayMode) =
        editSingleSetting { it[KEY_MONITORING_DISPLAY_MODE] = mode.name }

    /**
     * 設定を書き込む。**必ずスキーマ版も揃える。**
     *
     * 版が違う場合は**古い閾値を破棄**してから書く。単に版を揃えるだけだと、旧版で保存した閾値が
     * 「現行スキーマの値」として復活してしまうため(この破棄こそがスキーマ版の存在理由)。
     * ただし利用者が選んだスイッチ類([SWITCH_KEYS])は持ち越す。
     */
    private suspend fun editSingleSetting(block: (MutablePreferences) -> Unit) {
        val current = DayMvpSettings.DAY_MVP0_DEFAULT.settingsSchemaVersion
        context.settingsDataStore.edit { prefs ->
            if (prefs[KEY_SCHEMA_VERSION] != current) {
                // asMap()はビューなので、clear()の前にコピーを取る(filterKeysが新しいMapを返す)。
                val kept = prefs.asMap().filterKeys { it in SWITCH_KEYS }
                prefs.clear()
                kept.forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    prefs[key as Preferences.Key<Any>] = value
                }
                prefs[KEY_SCHEMA_VERSION] = current
            }
            block(prefs)
        }
    }

    private companion object {
        /** 閾値の保存キー。`id`は[TunableItem.id]。 */
        fun key(item: TunableItem) = floatPreferencesKey("tune_${item.id}")

        /** 保存時のDayMvpSettings.settingsSchemaVersion。現在の値と違えば閾値の保存内容を破棄する。 */
        val KEY_SCHEMA_VERSION = stringPreferencesKey("schema_version")

        /** 動作確認モード。製品運用ではOFF。[DayMvpSettings.testModeIgnoreSpeedGate]参照。 */
        val KEY_TEST_MODE_IGNORE_SPEED_GATE = booleanPreferencesKey("test_mode_ignore_speed_gate")
        val KEY_RECORD_LOCATION_ON_EVENT = booleanPreferencesKey("record_location_on_event")

        /** 運行中の表示モード(docs/17)。 */
        val KEY_MONITORING_DISPLAY_MODE = stringPreferencesKey("monitoring_display_mode")
        val KEY_PICTURE_IN_PICTURE_ENABLED = booleanPreferencesKey("picture_in_picture_enabled")
        val KEY_FRONT_CAMERA_ZOOM_RATIO = floatPreferencesKey("front_camera_zoom_ratio")
        val KEY_LOCATION_PROMPT_SUPPRESSED = booleanPreferencesKey("location_prompt_suppressed")
        val KEY_SHOW_DIAGNOSTIC_DETAILS = booleanPreferencesKey("show_diagnostic_details")

        /**
         * 利用者のスイッチ類。閾値ではないので、スキーマ版が変わっても破棄しない。
         * スイッチを増やしたらここにも足すこと。
         */
        val SWITCH_KEYS: Set<Preferences.Key<*>> = setOf(
            KEY_TEST_MODE_IGNORE_SPEED_GATE,
            KEY_RECORD_LOCATION_ON_EVENT,
            KEY_MONITORING_DISPLAY_MODE,
            KEY_PICTURE_IN_PICTURE_ENABLED,
            KEY_FRONT_CAMERA_ZOOM_RATIO,
            KEY_LOCATION_PROMPT_SUPPRESSED,
            KEY_SHOW_DIAGNOSTIC_DETAILS,
        )
    }
}
