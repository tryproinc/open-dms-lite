import java.util.Properties

import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

// Google Play へ上げる「アップロード鍵」の情報。android/keystore.properties(gitignore対象)があるときだけ署名する。
// 無いとき(他の人がクローンしてビルドするとき)は、これまでどおり未署名の release になる。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * 「このアプリについて」に出すビルド情報(2026-10-17)。実機に入っているのがどのビルドかを特定するため。
 * gitが使えない環境でもビルドは失敗させない。
 */
fun gitShortHash(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootProject.projectDir.parentFile) // android/ の親 = リポジトリルート
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && output.isNotEmpty()) output + dirtySuffix() else "unknown"
} catch (e: Exception) {
    "unknown"
}

/** 未コミットの変更(追跡中のファイル)があれば "-dirty"。コミット番号どおりのビルドか見分けるため。 */
fun dirtySuffix(): String = try {
    val process = ProcessBuilder("git", "status", "--porcelain", "--untracked-files=no")
        .directory(rootProject.projectDir.parentFile)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && output.isNotEmpty()) "-dirty" else ""
} catch (e: Exception) {
    ""
}

val buildTimeStr: String = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())

android {
    namespace = "com.trypro.opendms"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.co.trypro.opendms.lite"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.0-lite"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_SHA", "\"${gitShortHash()}\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTimeStr\"")
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // D0-001: debug buildはoverlay/診断用ログを許可する。中身は後続Issueで追加する。
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 11_DAY_SMARTPHONE_MVP_PLAN.md 3.1: release buildはdebug overlay/詳細frame logを無効化する。
            isDebuggable = false
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            // クラッシュ解析用: ネイティブ(MediaPipe)のシンボルをAABに同梱する(Play Consoleが読み取る)
            ndk.debugSymbolLevel = "SYMBOL_TABLE"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // D0-009: LocalSession/LocalEventにappVersionを記録するために使う(NFR-009)
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// AGP 9.0+のbuilt-in Kotlinではjvmターゲット等はkotlinOptions{}でなくこちらで指定する。
// https://developer.android.com/build/migrate-to-built-in-kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * Roomのスキーマ(JSON)を書き出す。
 *
 * **これが無いとマイグレーション試験が書けない。** 2026-09-07にマイグレーションの
 * テーブル名を間違えて起動不能にしており(docs/15 §9-3)、列を足す前に土台を入れた。
 * 書き出したJSONはgitに入れる ── 過去のスキーマが無いと、旧版のDBを再現できない。
 *
 * 2つ落とし穴があった。
 * - `@Database(exportSchema = false)` のままだと**何も出ない**(ここが本命だった)
 * - Room 2.6以降はkspの`room.schemaLocation`引数ではなく、
 *   **Room Gradleプラグイン**の`room { schemaDirectory(...) }`で指定する
 */
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mediapipe.tasks.vision)
    constraints {
        // MediaPipe が引き込む protobuf を修正版にする(libs.versions.toml の protobufJavalite)。
        implementation(libs.protobuf.javalite)
    }

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
