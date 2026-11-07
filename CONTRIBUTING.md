# 不具合報告・開発への参加

Open DMS Lite は MIT License のオープンソースです。ご報告・ご提案を歓迎します。ただし、ご報告への返信・修正、Pull Request の取り込みをお約束するものではありません。

## 不具合の報告

[GitHub Issues](https://github.com/tryproinc/open-dms-lite/issues) へお願いします。公開したくない内容は info@trypro.co.jp へ送ってください。

**Issues は誰でも読めます。次のものは載せないでください。**

- 顔の写った画像・動画
- 位置情報を含む CSV、運転者や車両を特定できる情報

報告に書いていただきたいこと:

- 端末の機種と Android の版
- アプリの版(前検査の画面の「このアプリについて」に出ます)
- 入手元(Google Play / APK / 自分でビルド)
- 起きたことと、期待したこと、再現の手順
- 明るさ・マスクやサングラスの有無など、そのときの条件

## 開発環境

- JDK 17 以上、Android SDK(compileSdk 36)。Android Studio でも `android/` を開けます
- 手順は [README の「ビルド」](README.md#ビルド) を参照してください

## Pull Request

1. `main` からブランチを切ります
2. 変更とテストを書きます(判定は `domain`・`vision` にあり、Android に依存せずテストできます)
3. 次が通ることを確認します(CI でも実行されます)

   ```bash
   cd android
   ./gradlew :app:testDebugUnitTest :app:lintDebug
   ```

4. Pull Request に、変更の内容と、確認したこと(実機の機種を含む)を書きます

### 変更するときの注意

- **閾値**: `domain/DayMvpSettings.kt` のコメントに根拠と経緯があります。読んでから変え、実データや合成データのテストで裏付けてください
- **プライバシー**: カメラ映像・顔画像・顔の特徴点を保存しないこと、`INTERNET` 権限を持たないことは、このアプリの前提です。崩す変更は受け付けません
- **プライバシーポリシー**: 変えるときは `docs/store/PRIVACY_POLICY.md` を直し、`python tools/make_privacy_html.py` でアプリ内の文章を作り直します(作り直し忘れはテストで落ちます)
- **DB**: 列を変えるときは Room のマイグレーションを足し、`android/app/schemas/` の JSON もコミットします
- **表現**: 「保安基準に適合」「事故を防ぐ」とは書きません([DISCLAIMER.md](DISCLAIMER.md))

送っていただいた変更は、このリポジトリの [MIT License](LICENSE) で提供されるものとして扱います。

---

© 2026 tryproinc  |  お問い合わせ・不具合報告: info@trypro.co.jp / 042-306-2924
