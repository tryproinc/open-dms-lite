package com.trypro.opendms.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trypro.opendms.vision.MountRotation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.mountOrientationDataStore by preferencesDataStore(name = "day_mvp0_mount_orientation")

/**
 * スマホの取付方向の手動指定。既定は自動検知([com.trypro.opendms.camera.MountOrientationSensor])。
 *
 * 取付が特殊(平置きに近い等)でセンサーが検知不能、または誤検知する場合に、利用者が
 * 手動で固定できるようにする。保存が無い、または不正な値の場合は`null`(=自動)を返す。
 */
class MountOrientationStore(private val context: Context) {

    val manualOverrideDegreesFlow: Flow<Int?> = context.mountOrientationDataStore.data.map { prefs ->
        prefs[KEY_MANUAL_DEGREES]?.takeIf { it in MountRotation.VALID_DEGREES }
    }

    /** @param degrees 0/90/180/270のいずれか。nullを渡すと自動検知へ戻す。 */
    suspend fun setManualOverride(degrees: Int?) {
        context.mountOrientationDataStore.edit { prefs ->
            if (degrees == null) {
                prefs.remove(KEY_MANUAL_DEGREES)
            } else {
                prefs[KEY_MANUAL_DEGREES] = degrees
            }
        }
    }

    private companion object {
        val KEY_MANUAL_DEGREES = intPreferencesKey("manual_mount_rotation_degrees")
    }
}
