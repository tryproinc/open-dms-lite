package com.trypro.opendms.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 車速(km/h)だけを供給する。**緯度経度は一切保持・記録しない。**
 *
 * ## 目的
 * 信号待ち・休憩・積卸し中の脇見誤報を消すための速度ゲート
 * ([com.trypro.opendms.domain.DayMvpSettings.distractionMinSpeedKph])に使う。
 * 眠気系(閉眼・こっくり・あくび)には使わない。
 *
 * ## 位置情報の扱い(06_PRIVACY_SECURITY_OPERATIONS.md)
 * 同文書は「GPS座標: MVP既定では不要 / 永続化しない」と定めている。本クラスは
 * [Location]から[Location.getSpeed]だけを読み取り、**緯度経度をフィールドにもログにも
 * 一切残さない**。[LocalSession][com.trypro.opendms.data.LocalSession]・
 * [LocalEvent][com.trypro.opendms.data.LocalEvent]・診断CSVのいずれにも書かない。
 * したがって位置情報保存に関する既存方針の変更にはあたらない。
 *
 * ## 実装方針
 * `FusedLocationProviderClient`(Google Play Services)ではなく素の[LocationManager]を使う。
 * Play Servicesへの依存を増やさないため。速度は測位点の差分ではなくGNSSチップが返す
 * ドップラー由来の値([Location.getSpeed])をそのまま使うので、[LocationManager]でも
 * 速度に関しては十分な精度が得られる。
 *
 * ## 速度が取れないとき
 * 測位前・トンネル・権限拒否のいずれでも[speedKph]は`null`のままになる。
 * **測位が[SPEED_MAX_AGE_MS]途絶えた・位置情報がオフにされたときも`null`に戻す**(公開前レビュー R-02)。
 * GPSは更新が止まっても最後の値を取り消さないので、戻さないと古い0km/hが残り、脇見と
 * 警告の開始([com.trypro.opendms.domain.MonitoringArming])を止め続ける。呼び出し側は
 * nullのとき速度ゲートを**開いたまま**にする(監視を止めない)。安全装置として
 * 「速度が分からないから監視しない」よりも「監視する」方が安全側であるため。
 *
 * ## Foreground Serviceについて
 * MVP-0の前提(アプリ前面固定・`FLAG_KEEP_SCREEN_ON`)が続く限り不要なので用意しない。
 * バックグラウンド動作を認めるときに改めて検討する。
 */
class SpeedProvider(private val appContext: Context) {

    private val locationManager: LocationManager? = appContext.getSystemService()

    private val _speedKph = MutableStateFlow<Float?>(null)
    /** 現在の車速(km/h)。取得できていない間はnull。 */
    val speedKph: StateFlow<Float?> = _speedKph.asStateFlow()

    private val _permissionGranted = MutableStateFlow(hasPermission())
    /** 位置情報権限が許可されているか。UIで「速度未取得」の理由を出し分けるために公開する。 */
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    private var listener: LocationListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最後の測位から[SPEED_MAX_AGE_MS]たったら速度を不明に戻す。 */
    private val expireSpeed = Runnable { _speedKph.value = null }

    /**
     * 位置を保持してよいか。**既定はfalseで、そのとき緯度経度には一切触れない**
     * (このクラスの元々の契約)。
     * [com.trypro.opendms.domain.DayMvpSettings.recordLocationOnEvent] で切り替える。
     */
    @Volatile
    var captureLocation: Boolean = false

    /** 直近の緯度。[captureLocation]がfalseの間は常にnull。 */
    @Volatile
    var lastLatitude: Double? = null
        private set

    /** 直近の経度。[captureLocation]がfalseの間は常にnull。 */
    @Volatile
    var lastLongitude: Double? = null
        private set

    /** 直近の測位の水平精度(m)。[captureLocation]がfalseの間は常にnull。 */
    @Volatile
    var lastAccuracyM: Float? = null
        private set

    /**
     * 直近の測位が得られた時刻(UTC ms)。[captureLocation]がfalseの間は常にnull。
     *
     * **「いつの座標か」が分からないと地図に打てない。**測位が途切れると
     * ここは古いまま止まるので、使う側が発報時刻との差を見て判断する。
     */
    @Volatile
    var lastFixedAtUtcMs: Long? = null
        private set

    /** 直近の測位の出所(`gps` / `network` など)。 */
    @Volatile
    var lastProvider: String? = null
        private set

    /**
     * `Location.hasSpeed()`がfalseの測位が続くとゲートが開きっぱなしになるため、
     * 「速度なしの測位」を受け取ったこと自体は記録しておく(診断用)。
     */
    @Volatile
    var lastFixHadSpeed: Boolean = false
        private set

    /**
     * [LocationManager.GPS_PROVIDER]には`ACCESS_FINE_LOCATION`が必要なので、FINEだけを見る。
     * Android 12以降、利用者はCOARSEだけを許可することもできるが、その場合ここはfalseを返し、
     * 速度はnullのまま=速度ゲートは開いたままになる(監視は止まらない)。
     */
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * 測位を開始する。権限がない場合は何もせず`false`を返す(呼び出し側は速度ゲートなしで続行する)。
     * 既に開始済みなら何もしない。
     */
    fun start(): Boolean {
        val granted = hasPermission()
        _permissionGranted.value = granted
        if (!granted) return false
        if (listener != null) return true

        val manager = locationManager ?: return false
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)

            // 利用者が位置情報をオフにした。測位は来なくなるので、待たずに不明へ戻す。
            override fun onProviderDisabled(provider: String) {
                mainHandler.removeCallbacks(expireSpeed)
                _speedKph.value = null
            }
        }
        return try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_UPDATE_INTERVAL_MS,
                0f,
                l,
                Looper.getMainLooper(),
            )
            listener = l
            true
        } catch (e: SecurityException) {
            // 権限チェックを通っていても、機種・OS設定によっては拒否され得る。
            Log.w(TAG, "requestLocationUpdates denied", e)
            _permissionGranted.value = false
            false
        } catch (e: IllegalArgumentException) {
            // GPS_PROVIDERを持たない端末。
            Log.w(TAG, "GPS provider unavailable", e)
            false
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        mainHandler.removeCallbacks(expireSpeed)
        _speedKph.value = null
        lastLatitude = null
        lastLongitude = null
        lastAccuracyM = null
        lastFixedAtUtcMs = null
        lastProvider = null
        try {
            locationManager?.removeUpdates(l)
        } catch (e: SecurityException) {
            Log.w(TAG, "removeUpdates denied", e)
        }
    }

    private fun onLocation(location: Location) {
        val hasSpeed = location.hasSpeed()
        lastFixHadSpeed = hasSpeed
        _speedKph.value = if (hasSpeed) location.speed * MPS_TO_KPH else null
        mainHandler.removeCallbacks(expireSpeed)
        if (hasSpeed) mainHandler.postDelayed(expireSpeed, SPEED_MAX_AGE_MS)
        // 緯度経度は**明示的にONにされたときだけ**保持する。
        // OFFの間は読みもせず、前回値も残さない(元々の契約を既定のまま保つ)。
        if (captureLocation) {
            lastLatitude = location.latitude
            lastLongitude = location.longitude
            lastAccuracyM = if (location.hasAccuracy()) location.accuracy else null
            // Location.time は測位の時刻(UTC ms)。端末の現在時刻ではない。
            lastFixedAtUtcMs = location.time.takeIf { it > 0L }
            lastProvider = location.provider
        } else {
            lastLatitude = null
            lastLongitude = null
            lastAccuracyM = null
            lastFixedAtUtcMs = null
            lastProvider = null
        }
    }

    private companion object {
        const val TAG = "SpeedProvider"

        /**
         * 測位間隔(ms)。速度ゲートは1秒単位で十分で、これ以上短くしても判定は変わらず
         * 消費電力だけ増える。
         */
        const val MIN_UPDATE_INTERVAL_MS = 1_000L

        /** 速度を使ってよい、最後の測位からの時間(ms)。測位間隔1秒の3回分。 */
        const val SPEED_MAX_AGE_MS = 3_000L

        /** m/s → km/h。 */
        const val MPS_TO_KPH = 3.6f
    }
}
