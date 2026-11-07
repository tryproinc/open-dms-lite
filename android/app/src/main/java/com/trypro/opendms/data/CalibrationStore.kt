package com.trypro.opendms.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trypro.opendms.domain.CalibrationProfile
import com.trypro.opendms.domain.DayMvpSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.calibrationDataStore by preferencesDataStore(name = "day_mvp0_calibration")

/**
 * 較正値をDataStoreへ保存する。11_DAY_SMARTPHONE_MVP_PLAN.md 3.3:
 * 「較正値はDataStoreへ数値だけ保存し、アプリ終了または取付変更で再較正する」。
 * 画像・landmark・生frameはここで一切扱わない([CalibrationProfile]は数値のみで構成される)。
 */
class CalibrationStore(private val context: Context) {

    suspend fun save(profile: CalibrationProfile) {
        val k = KEYS
        context.calibrationDataStore.edit { prefs ->
            prefs[k.yaw] = profile.baselineYawDeg
            prefs[k.pitch] = profile.baselinePitchDeg
            prefs[k.roll] = profile.baselineRollDeg
            prefs[k.eyeLeft] = profile.eyeOpenLeftBaseline
            prefs[k.eyeRight] = profile.eyeOpenRightBaseline
            prefs[k.faceSize] = profile.faceSizeBaseline
            prefs[k.calibratedAt] = profile.calibratedAtUtcMs
            prefs[k.settingsVersion] = profile.settingsVersion
            // 閉眼較正(2026-09-11)。無ければキーごと消して「未実施」を表す。
            val cl = profile.eyeClosedLeftBaseline
            val cr = profile.eyeClosedRightBaseline
            val th = profile.eyeClosedOpenRatioThreshold
            if (cl != null && cr != null && th != null) {
                prefs[k.eyeClosedLeft] = cl
                prefs[k.eyeClosedRight] = cr
                prefs[k.eyeClosedThreshold] = th
            } else {
                prefs.remove(k.eyeClosedLeft); prefs.remove(k.eyeClosedRight); prefs.remove(k.eyeClosedThreshold)
            }
        }
    }

    /** 較正値。無ければnull(=未較正)。 */
    val profileFlow: Flow<CalibrationProfile?> =
        context.calibrationDataStore.data.map { prefs ->
        val k = KEYS
        val calibratedAt = prefs[k.calibratedAt]
        val yaw = prefs[k.yaw]
        val pitch = prefs[k.pitch]
        val roll = prefs[k.roll]
        val eyeLeft = prefs[k.eyeLeft]
        val eyeRight = prefs[k.eyeRight]
        val faceSize = prefs[k.faceSize]
        val storedVersion = prefs[k.settingsVersion] ?: ""
        if (calibratedAt == null || yaw == null || pitch == null || roll == null ||
            eyeLeft == null || eyeRight == null || faceSize == null
        ) {
            null
        } else if (storedVersion != DayMvpSettings.DAY_MVP0_DEFAULT.version) {
            // 保存済み較正値の「意味」が変わったversionでは、古い値を使わず未較正として扱う。
            // 2026-08-29に頭部角度の軸割り当てを修正した際、既存の較正値(旧軸で測定)が
            // そのまま使われ続け、相対角度に誤ったオフセットが乗る不具合が発生した
            // (左右どちらか一方が脇見閾値に永久に到達しない状態になっていた)。
            // versionを上げるだけで自動的に再較正を促せるようにする。
            null
        } else {
            CalibrationProfile(
                baselineYawDeg = yaw,
                baselinePitchDeg = pitch,
                baselineRollDeg = roll,
                eyeOpenLeftBaseline = eyeLeft,
                eyeOpenRightBaseline = eyeRight,
                faceSizeBaseline = faceSize,
                calibratedAtUtcMs = calibratedAt,
                settingsVersion = storedVersion,
                // 閉眼較正は任意。古いプロファイルには無く、その場合は設定の既定閾値へ落ちる。
                eyeClosedLeftBaseline = prefs[k.eyeClosedLeft],
                eyeClosedRightBaseline = prefs[k.eyeClosedRight],
                eyeClosedOpenRatioThreshold = prefs[k.eyeClosedThreshold],
            )
        }
    }

    suspend fun clear() {
        context.calibrationDataStore.edit { it.clear() }
    }

    private companion object {
        val KEYS = Keys(
            yaw = floatPreferencesKey("baseline_yaw_deg"),
            pitch = floatPreferencesKey("baseline_pitch_deg"),
            roll = floatPreferencesKey("baseline_roll_deg"),
            eyeLeft = floatPreferencesKey("eye_open_left_baseline"),
            eyeRight = floatPreferencesKey("eye_open_right_baseline"),
            faceSize = floatPreferencesKey("face_size_baseline"),
            calibratedAt = longPreferencesKey("calibrated_at_utc_ms"),
            settingsVersion = stringPreferencesKey("settings_version"),
            eyeClosedLeft = floatPreferencesKey("eye_closed_left_baseline"),
            eyeClosedRight = floatPreferencesKey("eye_closed_right_baseline"),
            eyeClosedThreshold = floatPreferencesKey("eye_closed_open_ratio_threshold"),
        )
    }

    private class Keys(
        val yaw: androidx.datastore.preferences.core.Preferences.Key<Float>,
        val pitch: androidx.datastore.preferences.core.Preferences.Key<Float>,
        val roll: androidx.datastore.preferences.core.Preferences.Key<Float>,
        val eyeLeft: androidx.datastore.preferences.core.Preferences.Key<Float>,
        val eyeRight: androidx.datastore.preferences.core.Preferences.Key<Float>,
        val faceSize: androidx.datastore.preferences.core.Preferences.Key<Float>,
        val calibratedAt: androidx.datastore.preferences.core.Preferences.Key<Long>,
        val settingsVersion: androidx.datastore.preferences.core.Preferences.Key<String>,
        val eyeClosedLeft: androidx.datastore.preferences.core.Preferences.Key<Float>,
        val eyeClosedRight: androidx.datastore.preferences.core.Preferences.Key<Float>,
        val eyeClosedThreshold: androidx.datastore.preferences.core.Preferences.Key<Float>,
    )
}
