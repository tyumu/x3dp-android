# X3DP — Xteink X3 を Android の外部ディスプレイにする

電子ペーパー端末 **Xteink X3**(ESP32-C3 / 3.7インチ 528×792)を、Android スマホの
「外部モニタ + 物理ボタン付きページ送り機」として使うためのアプリです。

スマホの画面をユーザー補助サービスで取得 → 528×792 の 1bit 画像に変換 →
BLE または Wi-Fi で X3 へ転送 → X3 側の CrossPoint ファームが電子ペーパーに描画します。
X3 の物理ボタンを押すとコマンドが戻ってきて、アプリがスクロール / タップ / 再キャプチャを行います。

- ファームウェア側: [x3dp ブランチ (crosspoint-jp fork)](https://github.com/tyumu/crosspoint-jp/tree/x3dp)
  — 本家 [crosspoint-reader/crosspoint-reader](https://github.com/crosspoint-reader/crosspoint-reader) /
  日本語フォーク [zrn-ns/crosspoint-jp](https://github.com/zrn-ns/crosspoint-jp) の Net Display 機能です。

## できること

- **ミラー表示**: Chrome などの本文を X3 に表示。スクロールは X3 のボタンで行う
- **常駐サービス**: 画面を消しても転送を継続(フォアグラウンドサービス + ユーザー補助の見張り)
- **1タッチ起動**: X3 の Confirm 長押しで、スマホを点灯 → 対象アプリをポップアップ起動 → 1枚送信
- **ポップアップ(フリーフォーム)**: 対象アプリを任意サイズ・位置の浮きウィンドウで起動(Shizuku 任意)
- **ハードキー横取り**: 音量キーなどで X3 の操作をトリガできる(accessibility flags)
- **画質モード**: 単純2値 / ブルーノイズディザ / 8×8 Bayer ハーフトーン、4階調(2bpp)送信、閾値調整、クリーン更新
- **デバッグ**: 実際に送った画像を `Pictures/X3DP` へ保存、アプリ内にサービスログ表示

## 動作要件

| 項目 | 要件 |
| --- | --- |
| Android | 8.0 (API 26) 以上、実質 **11 (API 30) 以上**(`takeScreenshot` のため) |
| 権限 | ユーザー補助(X3DP を ON)、通知(13+)、Bluetooth(BLE 使用時) |
| X3 | CrossPoint 系ファーム + `Net Display`(`x3dp` ブランチ)。BLE モードでは X3 側も BLE 待ち受けになります |
| 任意 | Shizuku(ポップアップの位置・サイズを厳密に固定する場合) |

VPN (AdGuard / Tailscale) を常時 ON にしていても、Wi-Fi の `Network` にソケットを
`bindSocket()` するので LAN 内の X3 には届きます。

## ビルド

個人設定はリポジトリ直下の `.env` から焼き込みます(`.env.template` をコピー。`.env` は git 管理外)。
`X3_HOST` / `X3_PORT` / `X3_TRANSPORT` がビルド時の既定値になり、アプリ内の設定画面で上書きできます。
`.env` が無ければ `x3disp.local` / `5283` / `ble` が使われます。

```
# 前提: JDK 17+ (21 で確認), Android SDK (compileSdk 34 / build-tools 34+), Gradle 8.9
gradle assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease        # サイドロード用(release + minify + debug鍵署名, 約2.7MB)
```

- AGP 8.7.0 / Kotlin 2.0.21 / Gradle 8.9 / compileSdk 34 / minSdk 30
- `local.properties` に `sdk.dir=` を書く(Windows はフォワードスラッシュ推奨)
- Gradle wrapper は同梱していません。Android Studio で開くか `gradle wrapper` で生成してください
- release ビルドはデバッグ鍵で署名しています(配布用の署名鍵ではありません)

## 使い方

1. X3 で ホーム → **Net Display** を開く(BLE 待ち受け開始。表示に `ble X3DP-xx:xx:...` が出ます)
2. アプリの「接続」で **Bluetooth** を選ぶ(Wi-Fi ファームの場合は IP / ポート 5283)
3. 「ユーザー補助を開く」から X3DP を ON
4. 「連続モード開始」→ X3 のボタンで操作
5. 終了は「停止」。X3 は Net Display を抜けると元の Wi-Fi(OTA 用)へ戻ります

### ボタン → 操作(`ActionTable.kt` の既定)

| verb | id | 動作 |
| --- | --- | --- |
| `N` | 1 | 次へ(上スクロール 55%) |
| `P` | 2 | 前へ(下スクロール 55%) |
| `R` | 4 | 再取得(X3 側でフレームを捨てた時の再送要求) |
| `0x10+id` | n | 任意(アクション表で編集可) |

アプリ内「アクション表を既定に戻す」でリセットできます。

## 構成

```
app/src/main/java/net/x3dp/
  MainActivity.kt             設定 UI と状態表示
  DisplayService.kt           常駐サービス本体(転送ループ・ジェスチャ・ポップアップ起動)
  CaptureAccessibilityService.kt  画面取得・ジェスチャ注入・キーイベント横取り
  FrameEncoder.kt             キャプチャ画像 → 22Bヘッダ + 物理レイアウト1bit/2bpp
  BleClient.kt                BLE (Nordic UART Service 互換) クライアント
  X3Client.kt                 Wi-Fi TCP 5283 + UDP 5284 自動検出
  BlueNoise.kt                ディザ(ブルーノイズ / Bayer ハーフトーン)
  ActionTable.kt / Config.kt / ScreenSetting.kt / ShizukuHelper.kt / WakeActivity.kt
docs/protocol.md              転送プロトコル仕様
```

## 既知の制約

- `FLAG_SECURE` のウィンドウは取得できません(DRM 系・一部の銀行アプリ)
- `AccessibilityService.takeScreenshot()` は約 333ms のレート制限があります
- 入力を受け付けないアプリ(ゲームの raw input 等)にはジェスチャが効きません
- e-ink のリフレッシュ中に来たフレームは X3 側で破棄され、`R` を受けて再送します(連打しても最後の1枚が正しく出ます)
- 4階調(2bpp)は X3 のヒープとパネル波形の都合で実用上は 1bit + ディザ推奨です

## ライセンス

MIT (`LICENSE`)。ファームウェア側は CrossPoint 本家および crosspoint-jp の MIT ライセンスを継承します。
