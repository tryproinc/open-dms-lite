package com.trypro.opendms.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import androidx.room.RoomDatabase

/**
 * DAY MVP-0のRoomデータベース。11_DAY_SMARTPHONE_MVP_PLAN.md 3.7: 「Roomは2tableに限定する」。
 * MVP-0はまだ本番マイグレーション計画を必要としないため`exportSchema=false`とする
 * (pilotへ近づく段階でschema exportとマイグレーション方針を見直す)。
 */
@Database(entities = [LocalSession::class, LocalEvent::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * v2: イベントに 速度 / 緯度 / 経度 を追加(2026-09-07)。
         *
         * 破壊的マイグレーションにはしない ── 過去の運行記録は実証の資料なので消さない。
         * 3列とも NULL 許容なので、既存行は NULL のまま素直に読める。
         */
        /**
         * 適用するマイグレーションの一覧。**試験からも同じものを使う**
         * (試験だけ別の一覧を書くと、片方に足し忘れて意味を失う)。
         */
        fun migrations(): Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        /**
         * v3: 位置の質(精度・測位時刻・出所)と送信状態を追加(2026-09-08)。
         *
         * 発報を地図にタグとして打つには、緯度経度だけでは足りない(docs/16 §1-1)。
         * 送信状態は機能より先に列だけ用意する(後から足すと再マイグレーションになる)。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val t = DbTables.EVENT
                db.execSQL("ALTER TABLE $t ADD COLUMN locationAccuracyM REAL")
                db.execSQL("ALTER TABLE $t ADD COLUMN locationFixedAtUtcMs INTEGER")
                db.execSQL("ALTER TABLE $t ADD COLUMN locationProvider TEXT")
                db.execSQL("ALTER TABLE $t ADD COLUMN uploadedAtUtcMs INTEGER")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // **テーブル名はクラス名ではない。**必ず[DbTables]を経由すること
                // (2026-09-07に`no such table: LocalEvent`で起動のたびに落ちた)。
                val t = DbTables.EVENT
                db.execSQL("ALTER TABLE $t ADD COLUMN speedKph REAL")
                db.execSQL("ALTER TABLE $t ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE $t ADD COLUMN longitude REAL")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "open_dms.db",
                ).addMigrations(*migrations())
                    .build().also { instance = it }
            }
    }
}
