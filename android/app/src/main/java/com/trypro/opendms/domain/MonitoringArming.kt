package com.trypro.opendms.domain

/**
 * 運行開始後、一度[DayMvpSettings.armingSpeedKph]に達するまで警告を出さない(docs/19 §3-2a)。
 *
 * 出発前の準備(荷物・ミラー・ナビの操作)で手元を見たり動いたりしても鳴らさないため。
 * 一度達したら運行終了まで出し続ける(信号待ちで止めない)。判定と記録は待っている間も続ける。
 *
 * 速度が分からないまま監視が始まらない事態を避けるため、位置情報が未許可・動作確認モードなら最初から出し、
 * 許可済みでも速度不明(null)が[DayMvpSettings.armingNoSpeedFallbackMs]続いたら出し始める。
 * 一度速度が取れた後に測位が途絶えた場合も同じ(公開前レビュー R-02)。速度の鮮度は
 * [com.trypro.opendms.location.SpeedProvider]が見ていて、古い速度はnullで届く。
 * 運行ごとに作り直す。
 */
class MonitoringArming(private val settings: DayMvpSettings, startMs: Long) {

    var armed = false
        private set

    /** 最後に速度が取れた時刻。運行開始時は開始時刻から数える。 */
    private var lastSpeedMs = startMs

    fun update(nowMs: Long, speedKph: Float?, locationPermitted: Boolean): Boolean {
        if (armed) return true
        if (speedKph != null) lastSpeedMs = nowMs
        armed = settings.testModeIgnoreSpeedGate ||
            !locationPermitted ||
            (speedKph != null && speedKph >= settings.armingSpeedKph) ||
            nowMs - lastSpeedMs >= settings.armingNoSpeedFallbackMs
        return armed
    }
}
