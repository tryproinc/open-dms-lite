package com.trypro.opendms.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.trypro.opendms.BuildConfig

/**
 * アプリに含めているソフトウェア・モデル(2026-10-17)。ライセンス・著作権表示は各配布元の公開内容による。
 * 一覧はリポジトリの THIRD_PARTY_NOTICES.md と同じ。
 *
 * @property debugOnly 開発版(debug)だけに含まれるもの。製品版の画面には出さない。
 */
private data class LicenseEntry(
    val name: String,
    val license: String,
    val commercialUse: String,
    val copyright: String,
    val url: String,
    val debugOnly: Boolean = false,
)

private val LICENSES = listOf(
    LicenseEntry(
        "Kotlin / Kotlin Coroutines", "Apache License 2.0", "可",
        "Copyright 2010-2026 JetBrains s.r.o. and contributors",
        "https://github.com/JetBrains/kotlin",
    ),
    LicenseEntry(
        "AndroidX (Core KTX・Lifecycle・Activity Compose・Compose UI/Material3・Room・DataStore)",
        "Apache License 2.0", "可",
        "Copyright The Android Open Source Project",
        "https://developer.android.com/jetpack/androidx",
    ),
    LicenseEntry(
        "CameraX (camera-core/camera2/lifecycle/view)", "Apache License 2.0", "可",
        "Copyright The Android Open Source Project",
        "https://developer.android.com/training/camerax",
    ),
    LicenseEntry(
        "MediaPipe Tasks Vision", "Apache License 2.0", "可",
        "Copyright 2023 The MediaPipe Authors (Google LLC)",
        "https://github.com/google-ai-edge/mediapipe",
    ),
    LicenseEntry(
        "Protocol Buffers (protobuf-javalite。MediaPipe の依存)", "BSD 3-Clause License", "可",
        "Copyright 2008 Google Inc.",
        "https://github.com/protocolbuffers/protobuf",
    ),
    LicenseEntry(
        "MediaPipe Face Landmarker モデル (face_landmarker.task)", "Apache License 2.0", "可",
        "Copyright 2023 The MediaPipe Authors (Google LLC)。詳細はモデルカード参照",
        "https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker",
    ),
    LicenseEntry(
        "EfficientDet-Lite0 モデル (efficientdet_lite0.tflite。スマホ検出の診断。開発版のみ)", "Apache License 2.0", "可",
        "Google LLC。詳細はモデルカード参照",
        "https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector",
        debugOnly = true,
    ),
)

/**
 * 「このアプリについて」(2026-10-17)。rPPG Mental/Stress Analyzer の同名画面と同じ並び
 * (提供元 → アプリ情報 → 含めているソフトウェアの説明 → 一覧)にする。
 * アプリ情報はサポート・不具合報告で、どのビルドかを特定するために出す。
 * 文字は本文16sp以上(DADS)。前検査の「このアプリについて」から開き、戻るボタンか「閉じる」で閉じる。
 *
 * プライバシーポリシーの全文もここから読む(公開前レビュー R-05。Google Play はアプリ内での表示を求める)。
 * 本文は `assets/privacy_policy.txt`(`tools/make_privacy_html.py` が docs/store/PRIVACY_POLICY.md から作る)。
 */
@Composable
internal fun AboutPanel(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var showPolicy by rememberSaveable { mutableStateOf(false) }
    if (showPolicy) {
        PrivacyPolicyPanel(onBack = { showPolicy = false }, modifier = modifier)
        return
    }
    Surface(modifier = modifier, color = Color(0xFF101418)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "このアプリについて",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton("閉じる", onClick = onDismiss)
            }
            LazyColumn(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    AboutCard("提供元") {
                        AboutLine("株式会社トライプロ")
                        AboutLine("〒189-0001 東京都東村山市秋津町5-25-95 2F")
                        AboutLine("お問い合わせ・不具合報告: info@trypro.co.jp")
                        AboutLine("電話: 042-306-2924")
                        AboutLine("https://www.trypro.co.jp/")
                    }
                }
                item {
                    AboutCard("プライバシーポリシー") {
                        AboutLine("本アプリは通信をせず、映像・画像を保存しません。記録は端末の中だけにあります。")
                        SecondaryButton(
                            "全文を読む",
                            modifier = Modifier.padding(top = 8.dp),
                            onClick = { showPolicy = true },
                        )
                    }
                }
                item {
                    AboutCard("アプリ情報") {
                        AboutLine("Open DMS Lite(MIT License、Copyright (c) 2026 tryproinc)")
                        AboutLine("バージョン: ${BuildConfig.VERSION_NAME} (コード ${BuildConfig.VERSION_CODE})")
                        AboutLine("パッケージ名: ${BuildConfig.APPLICATION_ID}")
                        AboutLine("ビルド: ${if (BuildConfig.DEBUG) "開発版(debug)" else "製品版(release)"}")
                        AboutLine("ビルド日時: ${BuildConfig.BUILD_TIME}")
                        AboutLine("gitコミット: ${BuildConfig.GIT_SHA}")
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF263238))) {
                        Text(
                            "本アプリ(Open DMS Lite、MIT License)は、以下のソフトウェアとモデルを含んでいます。" +
                                "Apache License 2.0・BSD 3-Clause License はいずれも商用利用・改変・再配布を許諾する寛容型ライセンスです" +
                                "(著作権表示の保持など、ライセンスの条件は満たす必要があります)。" +
                                "再配布にあたっては、各配布元の最新のライセンスを直接ご確認ください。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White,
                        )
                    }
                }
                items(LICENSES.filter { BuildConfig.DEBUG || !it.debugOnly }) { entry ->
                    AboutCard(entry.name) {
                        AboutLine("${entry.license}  ·  商用利用: ${entry.commercialUse}", color = AccentAmber)
                        AboutLine(entry.copyright, color = SubtleText)
                        AboutLine(entry.url, color = SubtleText)
                    }
                }
                item { Text("", modifier = Modifier.padding(bottom = 16.dp)) }
            }
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C252B)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Column(modifier = Modifier.padding(top = 6.dp)) { content() }
        }
    }
}

@Composable
private fun AboutLine(text: String, color: Color = Color.White) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
}

/** プライバシーポリシーの全文。「■」で始まる行を見出しにする。戻るボタンか「戻る」で「このアプリについて」へ戻る。 */
@Composable
private fun PrivacyPolicyPanel(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val lines = remember {
        context.assets.open("privacy_policy.txt").bufferedReader().use { it.readLines() }
    }
    Surface(modifier = modifier, color = Color(0xFF101418)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "プライバシーポリシー",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton("戻る", onClick = onBack)
            }
            LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                items(lines) { line ->
                    if (line.startsWith("■")) {
                        Text(
                            line.removePrefix("■").trim(),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                    } else {
                        Text(line, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                    }
                }
                item { Text("", modifier = Modifier.padding(bottom = 16.dp)) }
            }
        }
    }
}
