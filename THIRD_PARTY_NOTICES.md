# サードパーティのソフトウェア・データ

Open DMS Lite 自身のライセンスは [LICENSE](LICENSE)(MIT License)です。
配布物には、次のソフトウェアとモデルが含まれます。Protocol Buffers だけは BSD 3-Clause License、ほかは Apache License 2.0 で提供されています。

| 名称 | 用途 | ライセンス |
|---|---|---|
| [MediaPipe Tasks Vision](https://github.com/google-ai-edge/mediapipe) (`com.google.mediapipe:tasks-vision`) | 顔のランドマーク・表情・頭部姿勢の推定 | Apache License 2.0 |
| `face_landmarker.task`(Google が配布する [MediaPipe Face Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker) のモデル。下の3つをまとめたもの) | 同上のモデル(`android/app/src/main/assets/`) | Apache License 2.0 |
| [Protocol Buffers](https://github.com/protocolbuffers/protobuf) (`com.google.protobuf:protobuf-javalite`、MediaPipe の依存) | MediaPipe の設定・モデルの読み込み | BSD 3-Clause License(Copyright 2008 Google Inc.) |
| AndroidX(Core・Lifecycle・Activity・CameraX・Room・DataStore) | アプリの基盤、カメラ、端末内 DB、設定保存 | Apache License 2.0 |
| Jetpack Compose・Material 3 | 画面 | Apache License 2.0 |
| Kotlin 標準ライブラリ・kotlinx.coroutines | 言語・非同期処理 | Apache License 2.0 |

テスト専用の依存(JUnit、Truth、Robolectric、kotlinx-coroutines-test、AndroidX Test)は配布物に含まれません。

## 顔解析モデルのモデルカード

`face_landmarker.task` の3つのモデルのライセンスは、Google のモデルカードで Apache License 2.0 と確認しました(2026-11-07)。

- [BlazeFace (Short Range)](https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Short%20Range).pdf)
- [Face Mesh V2](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20MediaPipe%20Face%20Mesh%20V2.pdf)
- [Blendshape V2](https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf)

**モデルの想定用途についての注意**: Face Mesh V2 と Blendshape V2 のモデルカードは、主な想定用途を AR エンターテインメントとし、対象外の用途として「This model is not intended for human life-critical decisions.」と記載しています。ライセンス上の禁止事項ではありませんが、Open DMS Lite の検知は安全を保証するものではありません。警告だけに頼らず、運転の判断は運転者が行ってください([DISCLAIMER.md](DISCLAIMER.md))。

## 開発版の診断用モデル(同梱していません)

開発版(debug)には、スマートフォン検出の**診断計測**(判定・警告には使いません)の機能があり、MediaPipe Object Detector の `efficientdet_lite0.tflite` を使います。release ビルドには入りません。

配布元でこのモデルのライセンス表記を確認できなかったため、このリポジトリには同梱していません。無くてもビルド・動作に支障はなく、診断計測だけが止まります。使う場合は、[配布元](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector)の条件を確認のうえ、`android/app/src/debug/assets/` に置いてください。

## ライセンスの全文

- Apache License 2.0: <https://www.apache.org/licenses/LICENSE-2.0>
- BSD 3-Clause License(Protocol Buffers): <https://github.com/protocolbuffers/protobuf/blob/main/LICENSE>

---

© 2026 tryproinc  |  お問い合わせ・不具合報告: info@trypro.co.jp / 042-306-2924
