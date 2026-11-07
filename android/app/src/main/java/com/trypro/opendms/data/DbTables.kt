package com.trypro.opendms.data

/**
 * Roomのテーブル名。
 *
 * ## なぜ定数にするのか
 *
 * テーブル名は `@Entity(tableName = ...)` で決まり、**クラス名とは一致しない**。
 * 2026-09-07に、マイグレーションでクラス名(`LocalEvent`)を書いてしまい
 * `no such table: LocalEvent` で**起動のたびにクラッシュ**した。
 *
 * ```
 * SQLiteException: no such table: LocalEvent
 *   while compiling: ALTER TABLE LocalEvent ADD COLUMN speedKph REAL
 * ```
 *
 * 生SQLを書くのはマイグレーションだけなので、そこと `@Entity` が**同じ定数を参照**すれば
 * この食い違いは起こりえない。文字列を2箇所に書かないこと。
 */
object DbTables {
    const val SESSION = "local_session"
    const val EVENT = "local_event"
}
