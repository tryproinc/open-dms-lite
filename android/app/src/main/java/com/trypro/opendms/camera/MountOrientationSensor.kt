package com.trypro.opendms.camera

import android.content.Context
import android.hardware.SensorManager
import android.view.OrientationEventListener
import com.trypro.opendms.vision.MountRotation

/**
 * スマホの物理的な取付回転角度を検知する。
 *
 * `AndroidManifest.xml`は`screenOrientation="portrait"`に固定しているため、通常の画面回転
 * コールバック(`Display.getRotation()`等)は変化しない。[OrientationEventListener]は
 * 画面ロックとは独立に加速度センサーから実際の物理回転角を継続的に検知できるため、これを使う
 * (一次情報の確認と符号の導出は[MountRotation]のKDoc参照)。
 *
 * デバイスがほぼ水平(平置き)に近いと重力方向だけでは回転を判別できず`null`になる
 * (ダッシュボード上のホルダー設置では通常発生しない想定)。
 */
class MountOrientationSensor(context: Context) {

    @Volatile
    var detectedRotationDegrees: Int? = null
        private set

    private val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(orientation: Int) {
            detectedRotationDegrees = MountRotation.quantizeTo90(orientation)
        }
    }

    /** センサーが搭載されておらず検知不能な端末かどうか(電源投入直後は不明を返し得る)。 */
    fun canDetect(): Boolean = listener.canDetectOrientation()

    fun start() {
        listener.enable()
    }

    fun stop() {
        listener.disable()
    }
}
