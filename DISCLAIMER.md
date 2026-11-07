# 免責事項 / Disclaimer

Open DMS Lite をご利用になる前に、必ずお読みください。
(英語の要約は末尾にあります。)

## 1. 位置付け

- Open DMS Lite は、スマートフォンのインカメラで運転者の**眠気の兆候**と**前方以外を長く見ていること**(脇見)を検知し、音・振動・画面で知らせる、**無償のオープンソース(MIT License)の運転支援アプリ**です
- **医療機器ではありません。** 健康状態の診断はしません
- **事故を防げることを保証しません。** 警告が出ないことは、安全であることを意味しません
- 運転の責任は、常に運転者にあります

## 2. 保安基準・国際基準への適合を保証しません

- 道路運送車両の保安基準(第43条の11 眠気警報装置・第43条の12 脇見警報装置)、およびこれに対応する **UN-R182・UN-R183 への適合を保証するものではありません**
- 型式認証・国土交通省等による認定・認証を受けた装置ではなく、これらを満たすことを目的とした製品でもありません
- 基準の考え方を参考に設計した部分がありますが、**満たせない項目があります**(夜間の動作、車両との連動、運転者による停止の制限、効果検証の未実施など)。項目ごとの対応状況は [docs/COMPLIANCE.md](docs/COMPLIANCE.md) を参照してください
- 後付けのスマートフォンアプリは、保安基準が定める警報装置の代わりにはなりません。保安基準への対応が必要な車両では、基準に適合した装置をご利用ください

## 3. 機能の限界

次の場合などには、正しく動作しないことがあります。検知の漏れ・誤報がありえます。

- 暗い場所・夜間・トンネル・逆光
- マスク・帽子・サングラスなど、顔や頭に装着物がある場合(マスク着用時はあくびを検知できません)
- 顔がカメラの画角から外れている、遮蔽物がある、レンズが汚れている
- 端末が高温になった場合、GPS の速度が取得できない場合

顔の解析に使う Google の MediaPipe のモデルは、主な想定用途が AR エンターテインメントで、人命に関わる判断には想定されていません([THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md))。本アプリの検知だけに頼らないでください。

詳細は [docs/USER_MANUAL.md](docs/USER_MANUAL.md) と [docs/SPECIFICATION.md](docs/SPECIFICATION.md) を参照してください。

## 4. 無保証・免責

- 本ソフトウェアは **「現状のまま」(AS IS)** 提供され、明示・黙示を問わず、いかなる保証もありません(商品性、特定目的への適合性、非侵害を含みます)。詳細は [LICENSE](LICENSE) のとおりです
- 本ソフトウェアの使用または使用できなかったことによって生じたいかなる損害(人身事故・物損・営業上の損失を含みます)についても、開発者および著作権者は責任を負いません

## 5. 利用者(事業者を含む)の責任

- 事業用車両などで従業員に使わせる場合、**従業員への説明、就業規則・労使の取り決め、個人情報の取扱い**は、利用者の責任で行ってください
- 本アプリは、カメラ映像・顔画像・顔の特徴点を端末にも外部にも保存せず、ネットワーク通信もしません。記録するのは警告の種類・時刻・継続時間・速度などの数値だけです。位置情報は速度の判定に使い、既定では記録しません(設定で「警告が出た地点」だけ記録するよう変えられます)
- 運転中にスマートフォンを操作しないでください。設定や確認は停車中に行ってください

## 6. お問い合わせ・不具合報告

不具合の報告・ご意見・Full 版のご相談は、メール(info@trypro.co.jp)または電話(042-306-2924)へ。報告に添える内容は [取扱説明書 §13](docs/USER_MANUAL.md) を参照してください(顔の写った画像・動画は送らないでください)。ご報告への返信・修正をお約束するものではありません。

## English summary

Open DMS Lite is a free, open-source (MIT License) driver-assistance app that uses a smartphone's front camera to detect signs of drowsiness and long glances away from the road, and alerts the driver by sound, vibration and screen.

- It is **not a medical device**, and it does **not guarantee** accident prevention. The driver is always responsible for driving.
- It is **not certified or type-approved**, and **does not claim compliance** with Japan's road vehicle safety standards (Articles 43-11 and 43-12) or UN Regulations No. 182 and No. 183. Some requirements cannot be met (night operation, vehicle integration, tamper resistance, validation tests). See [docs/COMPLIANCE.md](docs/COMPLIANCE.md).
- Detection may be missed or wrong (darkness, masks, sunglasses, occlusion, hot device, no GPS speed, and so on). The MediaPipe face models it relies on are intended mainly for AR entertainment, not for human life-critical decisions (see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).
- The software is provided "AS IS", without warranty of any kind. The authors are not liable for any damages arising from its use. See [LICENSE](LICENSE).
- Operators who deploy it to employees are responsible for notice, labor agreements and privacy compliance.
- Bug reports and inquiries: info@trypro.co.jp / +81-42-306-2924. Please do not send images or videos that show a face.

<!--pagebreak-->

## 発行元・お問い合わせ

| | |
|---|---|
| 社名 | 株式会社トライプロ |
| 所在地 | 〒189-0001 東京都東村山市秋津町5-25-95 2F |
| 電話番号 | 042-306-2924 |
| FAX番号 | 050-3488-7454 |
| E-mail | info@trypro.co.jp |
| URL | https://www.trypro.co.jp/ |

お問い合わせ・不具合報告は、上記へお願いします。

---

© 2026 tryproinc  |  お問い合わせ・不具合報告: info@trypro.co.jp / 042-306-2924
