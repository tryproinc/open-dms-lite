package com.trypro.opendms.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 1件の安全イベント(閉眼/脇見/監視不能)。11_DAY_SMARTPHONE_MVP_PLAN.md 3.7:
 * 座標・画像・動画・顔landmark・氏名を一切持たせない。`type`/`severity`は
 * [com.trypro.opendms.domain.AlertType]/[com.trypro.opendms.domain.AlertSeverity]の`name`をそのまま使う。
 */
@Entity(tableName = DbTables.EVENT)
data class LocalEvent(
    @PrimaryKey val id: String,
    val sessionId: String,
    val type: String,
    val severity: String,
    val startedAtUtcMs: Long,
    val endedAtUtcMs: Long,
    val durationMs: Long,
    /** [com.trypro.opendms.vision.QualityFlag]名をカンマ区切りにしたもの。座標・画像は含まない。 */
    val qualityFlags: String,
    val confidence: Float?,
    val appVersion: String,
    val modelVersion: String,
    val settingsVersion: String,
    /**
     * 発報した瞬間の車速(km/h)。GPSが未測位なら null。
     *
     * 「何km/hで出た警告か」は、後から妥当性を判断するのに要る
     * (停車中の脇見警告と高速走行中の脇見警告は意味がまったく違う)。
     */
    val speedKph: Float? = null,
    /**
     * 発報した地点の緯度。**[DayMvpSettings.recordLocationOnEvent] がONのときだけ入る。**
     *
     * 既定はOFFで、そのとき値は null。
     * 位置の記録は運転者の個人データの扱いが変わるので、
     * ONにすると運転者向けの周知文([DriverNoticeSections])の記載も切り替わる。
     */
    val latitude: Double? = null,
    /** 発報した地点の経度。[latitude]と同じ条件でだけ入る。 */
    val longitude: Double? = null,
    /**
     * 測位の水平精度(m)。大きいほど地図上の点は信用できない。
     *
     * **地図にピンを打つなら必須。**誤差50mの測位と誤差5mの測位を区別できないと、
     * 市街地のビル影で数十mずれた点を「そこで起きた」として扱ってしまう。
     */
    val locationAccuracyM: Float? = null,
    /**
     * その測位が得られた時刻(UTC ms)。**発報時刻との差が「座標の古さ」になる。**
     *
     * `SpeedProvider`は最後の測位を保持し続けるので、GPSが切れた区間では
     * 数分前の座標が残る(トンネル・高架下で確実に起きる)。
     * 古すぎる測位は[DayMvpSettings.eventLocationMaxAgeMs]で弾いて位置をnullにするが、
     * 弾かなかったものについても**どれくらい古いかを後から判断できるように残す**。
     */
    val locationFixedAtUtcMs: Long? = null,
    /** 測位の出所(`gps` / `network` など)。精度の意味が変わるので残す。 */
    val locationProvider: String? = null,
    /**
     * サーバへ送れた時刻(UTC ms)。未送信ならnull。
     *
     * **送信機能そのものはまだ無いが、列を先に用意しておく。**
     * 送信は圏外・失敗・再起動をまたぐので、どれを送ったかを端末側で持つ必要がある。
     * [id]がUUIDなので、これと合わせて**べき等な再送**ができる。
     * 後から足すと再マイグレーションになるので、いま入れておく。
     */
    val uploadedAtUtcMs: Long? = null,
)
