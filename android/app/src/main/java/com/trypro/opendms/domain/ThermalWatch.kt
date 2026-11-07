package com.trypro.opendms.domain

/**
 * 端末が高温かどうか(docs/19 §6-6)。**端末の申告(`thermalStatus`)の絶対値では決めない。**
 *
 * Nothing Phone (2a)は冷えていてもMODERATE〜SEVEREを返す(充電中は特に)。そのため、これまでに見た最低値より
 * 上がり、かつSEVERE以上のときだけ高温とする。高温の警告([DayDmsStateMachine])と画面の「高温」表示で共用する。
 */
class ThermalWatch {
    private var floor: Int? = null

    /** [thermalStatus]を記録し、高温ならtrue。 */
    fun update(thermalStatus: Int): Boolean {
        val f = minOf(floor ?: thermalStatus, thermalStatus)
        floor = f
        return thermalStatus >= THERMAL_STATUS_SEVERE && thermalStatus > f
    }
}

/** `android.os.PowerManager.THERMAL_STATUS_SEVERE`。domainはAndroid非依存を保つため定数で持つ。 */
private const val THERMAL_STATUS_SEVERE = 3
