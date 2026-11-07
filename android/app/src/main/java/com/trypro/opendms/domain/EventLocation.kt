package com.trypro.opendms.domain

/**
 * 発報に添える位置。**古すぎる測位は載せない**(docs/16 §1-1)。
 *
 * `SpeedProvider`は最後の測位を保持し続けるので、GPSが切れた区間では数分前の座標が残る
 * (トンネル・高架下で確実に起きる)。それを地図に打つと
 * **その場所で起きていないことを記録する**ことになる。嘘の座標を残すより無いほうがよい。
 */
data class EventLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Float? = null,
    val fixedAtUtcMs: Long? = null,
    val provider: String? = null,
) {
    companion object {
        /** 位置なし。設定がOFF、未測位、または測位が古すぎるとき。 */
        val NONE = EventLocation()

        /**
         * 直近の測位から、発報に添えてよい位置を作る。
         *
         * @param nowUtcMs 発報の時刻(UTC ms)
         * @param maxAgeMs これより古い測位は捨てる([DayMvpSettings.eventLocationMaxAgeMs])
         */
        fun from(
            enabled: Boolean,
            latitude: Double?,
            longitude: Double?,
            accuracyM: Float?,
            fixedAtUtcMs: Long?,
            provider: String?,
            nowUtcMs: Long,
            maxAgeMs: Long,
        ): EventLocation {
            if (!enabled) return NONE
            if (latitude == null || longitude == null) return NONE
            // 測位時刻が分からないものも捨てる。「いつの座標か」が言えないものは地図に打てない。
            if (fixedAtUtcMs == null) return NONE
            val age = nowUtcMs - fixedAtUtcMs
            // 未来の時刻(端末の時計ずれ)も信用しない。
            if (age < 0L || age > maxAgeMs) return NONE
            return EventLocation(latitude, longitude, accuracyM, fixedAtUtcMs, provider)
        }
    }
}
