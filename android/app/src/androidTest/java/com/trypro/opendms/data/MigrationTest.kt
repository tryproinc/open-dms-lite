package com.trypro.opendms.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roomのマイグレーション試験。
 *
 * ## なぜ要るのか
 *
 * 2026-09-07に、マイグレーションで**テーブル名にクラス名を書いて**しまい、
 * 運行を始めた瞬間に落ちるアプリを配ってしまった。
 *
 * ```
 * SQLiteException: no such table: LocalEvent
 *   while compiling: ALTER TABLE LocalEvent ADD COLUMN speedKph REAL
 * ```
 *
 * 単体試験はすべて通っていた。**SQLは実際にSQLiteへ流さないと確かめられない。**
 *
 * ## なぜ`MigrationTestHelper`を使わないのか
 *
 * `room-testing` 2.8.4 のヘルパはスキーマJSONの読み込みに kotlinx-serialization 1.8 を要求するが、
 * このプロジェクトの依存は `strictly 1.7.3` で固定されており、実行時に
 * `AbstractMethodError: GeneratedSerializer.typeParametersSerializers()` で落ちる。
 * 依存の綱引きを試験のために続けるより、**同じことをより単純に確かめる**。
 *
 * ここでやっているのはこれだけ:
 *
 * 1. 旧版のDBを**生SQLで作る**(CREATE文は`app/schemas/…/2.json`の`createSql`をそのまま写した)
 * 2. `user_version`を旧版にする
 * 3. **Roomに開かせる** ── Roomがマイグレーションを流し、**結果のスキーマを自分で検証する**
 * 4. DAO経由で読み書きする
 *
 * 3でRoomがスキーマ不一致を見つければ例外を投げる。9/7に落ちたのは1〜3の途中である。
 *
 * 実機/エミュレータが要る(`./gradlew connectedDebugAndroidTest`)。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-test.db"

    @Before
    fun clean() {
        context.deleteDatabase(dbName)
    }

    /**
     * v2のスキーマ。`app/schemas/com.trypro.opendms.data.AppDatabase/2.json` の
     * `createSql` を写したもの。**手で書き換えないこと。**
     */
    private fun createV2Database() {
        val path = context.getDatabasePath(dbName).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `local_session` (`id` TEXT NOT NULL, " +
                    "`startedAtUtcMs` INTEGER NOT NULL, `endedAtUtcMs` INTEGER, `status` TEXT NOT NULL, " +
                    "`monitoringMs` INTEGER NOT NULL, `validMonitoringMs` INTEGER NOT NULL, " +
                    "`appVersion` TEXT NOT NULL, `modelVersion` TEXT NOT NULL, " +
                    "`settingsVersion` TEXT NOT NULL, `deviceModel` TEXT NOT NULL, " +
                    "`osVersion` TEXT NOT NULL, PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `local_event` (`id` TEXT NOT NULL, " +
                    "`sessionId` TEXT NOT NULL, `type` TEXT NOT NULL, `severity` TEXT NOT NULL, " +
                    "`startedAtUtcMs` INTEGER NOT NULL, `endedAtUtcMs` INTEGER NOT NULL, " +
                    "`durationMs` INTEGER NOT NULL, `qualityFlags` TEXT NOT NULL, `confidence` REAL, " +
                    "`appVersion` TEXT NOT NULL, `modelVersion` TEXT NOT NULL, " +
                    "`settingsVersion` TEXT NOT NULL, `speedKph` REAL, `latitude` REAL, " +
                    "`longitude` REAL, PRIMARY KEY(`id`))",
            )
            // Roomはこのテーブルで版の同一性を確かめる。無いと「作り直し」と見なされる。
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS room_master_table " +
                    "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
            )
            db.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                    "VALUES (42, '$V2_IDENTITY_HASH')",
            )
            db.execSQL(
                "INSERT INTO local_session " +
                    "(id, startedAtUtcMs, endedAtUtcMs, status, monitoringMs, validMonitoringMs, " +
                    "appVersion, modelVersion, settingsVersion, deviceModel, osVersion) " +
                    "VALUES ('s1', 1000, 2000, 'COMPLETED', 1000, 900, 'v', 'm', 'set', 'dev', '16')",
            )
            db.execSQL(
                "INSERT INTO local_event " +
                    "(id, sessionId, type, severity, startedAtUtcMs, endedAtUtcMs, durationMs, " +
                    "qualityFlags, confidence, appVersion, modelVersion, settingsVersion, speedKph) " +
                    "VALUES ('e1', 's1', 'EYE_CLOSURE', 'CAUTION', 1000, 2500, 1500, " +
                    "'', 0.9, 'v', 'm', 'set', 42.0)",
            )
            db.version = 2
        }
    }

    private fun openRoom(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.migrations())
            .build()

    /**
     * v2 → 最新。**Roomが開けること**と、**既存の行が消えないこと**を見る
     * (過去の運行記録は実証の資料なので、破壊的マイグレーションにしていない)。
     */
    @Test
    fun v2から最新へ移行でき既存の行が残る() = runBlocking {
        createV2Database()

        val db = openRoom()
        try {
            // ここでRoomがマイグレーションを流し、スキーマを検証する。
            val events = db.eventDao().forSession("s1")
            assertThat(events).hasSize(1)

            val e = events.single()
            // v2までに入っていた値は残る。
            assertThat(e.speedKph).isNotNull()
            assertThat(e.speedKph!!).isWithin(1e-3f).of(42f)
            // v3で足した列は既存行ではnull。
            assertThat(e.locationAccuracyM).isNull()
            assertThat(e.locationFixedAtUtcMs).isNull()
            assertThat(e.locationProvider).isNull()
            assertThat(e.uploadedAtUtcMs).isNull()
        } finally {
            db.close()
        }
    }

    /** 移行後に**新しい列へ書いて読み戻せる**こと。列だけ足って使えない、を防ぐ。 */
    @Test
    fun 移行後に新しい列へ書いて読み戻せる() = runBlocking {
        createV2Database()

        val db = openRoom()
        try {
            db.eventDao().insert(
                LocalEvent(
                    id = "e-new",
                    sessionId = "s1",
                    type = "DISTRACTION",
                    severity = "WARNING",
                    startedAtUtcMs = 10,
                    endedAtUtcMs = 20,
                    durationMs = 10,
                    qualityFlags = "",
                    confidence = null,
                    appVersion = "v",
                    modelVersion = "m",
                    settingsVersion = "s",
                    speedKph = 55f,
                    latitude = 35.681236,
                    longitude = 139.767125,
                    locationAccuracyM = 8f,
                    locationFixedAtUtcMs = 15,
                    locationProvider = "gps",
                    uploadedAtUtcMs = null,
                ),
            )
            val e = db.eventDao().forSession("s1").single { it.id == "e-new" }
            assertThat(e.latitude!!).isWithin(1e-6).of(35.681236)
            assertThat(e.locationAccuracyM!!).isWithin(1e-3f).of(8f)
            assertThat(e.locationProvider).isEqualTo("gps")
            assertThat(e.uploadedAtUtcMs).isNull()
        } finally {
            db.close()
        }
    }

    private companion object {
        /**
         * `app/schemas/com.trypro.opendms.data.AppDatabase/2.json` の `identityHash`。
         * これが合っていないとRoomは「別のスキーマ」と見なして作り直そうとする。
         */
        const val V2_IDENTITY_HASH = "f34c7903b6b0697a4d3c3dfc52d32416"
    }
}
