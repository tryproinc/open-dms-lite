// ルートビルドファイル。全モジュール共通のプラグインをここで宣言だけし、適用はapp/build.gradle.ktsで行う。
// AGP 9.0+はbuilt-in Kotlinを既定で持つため、org.jetbrains.kotlin.androidプラグインは適用しない。
// https://developer.android.com/build/releases/agp-9-0-0-release-notes (Built-in Kotlin)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
