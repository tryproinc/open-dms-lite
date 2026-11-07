package com.trypro.opendms.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 発報を緯度経度つきのCSVへ書き出す(docs/16 §2 P0-4)。
 *
 * ## なぜサーバより先にこれなのか
 *
 * サーバの送信先・認証・保持期間はまだ決まっていない。
 * **CSVがあれば、今日から QGIS や uMap にそのまま読ませて地図に打てる。**
 * しかも送信は「このCSVと同じ内容をHTTPで送るだけ」になるので、
 * **先に形式を固めておくと後の実装が楽になる**。
 *
 * 保存先は診断CSVと同じ `getExternalFilesDir` 配下。
 * PCへUSB接続すればそのまま取り出せる(アプリ専用領域、権限不要)。
 *
 * ## 位置が空の行について
 *
 * 位置は「設定がONで、かつ測位が新しいとき」だけ入る
 * ([com.trypro.opendms.domain.EventLocation])。空欄は
 * 「そこで起きなかった」ではなく「**どこで起きたか言えない**」を意味する。
 * 地図に打つときはその行を落とすこと。
 */
class EventCsvExporter(private val context: Context) {

    /**
     * @return 利用者に見せる短い結果(書き出し先、または失敗の理由)
     */
    fun export(sessionId: String, events: List<LocalEvent>): String {
        if (events.isEmpty()) return "書き出す発報がありません"
        return try {
            val dir = File(context.getExternalFilesDir(null), "events").apply { mkdirs() }
            val file = File(dir, "events_$sessionId.csv")
            file.bufferedWriter().use { w ->
                w.appendLine(HEADER)
                events.forEach { w.appendLine(toLine(it)) }
            }
            "書き出しました: ${file.parentFile?.name}/${file.name} (${events.size}件)"
        } catch (t: Throwable) {
            Log.w(TAG, "CSV書き出しに失敗", t)
            "書き出しに失敗しました: ${t.message}"
        }
    }

    private fun toLine(e: LocalEvent): String {
        // 位置の「古さ」は毎回引き算するより、書き出しておいたほうが後で扱いやすい。
        val ageMs = e.locationFixedAtUtcMs?.let { e.endedAtUtcMs - it }
        return listOf(
            e.id,
            e.sessionId,
            e.type,
            e.severity,
            iso(e.startedAtUtcMs),
            iso(e.endedAtUtcMs),
            e.durationMs.toString(),
            e.speedKph?.let { "%.1f".format(it) } ?: "",
            e.latitude?.let { "%.7f".format(it) } ?: "",
            e.longitude?.let { "%.7f".format(it) } ?: "",
            e.locationAccuracyM?.let { "%.1f".format(it) } ?: "",
            ageMs?.toString() ?: "",
            e.locationProvider ?: "",
            e.qualityFlags,
            e.confidence?.let { "%.3f".format(it) } ?: "",
            e.appVersion,
            e.modelVersion,
            e.settingsVersion,
        ).joinToString(",") { escape(it) }
    }

    /** カンマ・引用符・改行を含む値をCSVとして正しく囲む。 */
    private fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else {
            v
        }

    private fun iso(utcMs: Long): String = ISO.format(Date(utcMs))

    private companion object {
        const val TAG = "EventCsvExporter"

        /**
         * 列。**サーバへ送るときもこの並びを使う**(形式を1つに保つ)。
         *
         * `locationAgeMs` は発報時刻と測位時刻の差。大きいほど座標は信用できない。
         */
        const val HEADER =
            "eventId,sessionId,type,severity,startedAtUtc,endedAtUtc,durationMs," +
                "speedKph,latitude,longitude,locationAccuracyM,locationAgeMs,locationProvider," +
                "qualityFlags,confidence,appVersion,modelVersion,settingsVersion"

        /** UTCで書く。地図側でローカル時刻へ直せばよく、取り違えが起きない。 */
        val ISO: SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
    }
}
