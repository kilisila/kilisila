# パララックス壁紙

端末の傾きに合わせて 3 枚のレイヤーが異なる量で動き、奥行きを感じさせる Android のライブ壁紙です。
`WallpaperService` と Canvas のみで実装しており、実行時に使うサードパーティのライブラリはありません（JUnit は単体テスト専用で、APK には含まれません）。

## 仕様

| 項目 | 内容 |
| --- | --- |
| 背景 `background.png` | 傾き最大時に画面の 5% 移動。端に隙間が出ないよう 1.2 倍で描画 |
| 光 `glow.png` | 画面の 12% 移動。不透明度 70〜100% を 4 秒周期で明滅 |
| 前景 `foreground.png` | 画面の 25% 移動。背景と逆方向 |
| ドロップシャドウ | 前景の不透明部分をぼかして生成。前景の 6 割だけ動かし、浮いて見せる |
| センサー | `TYPE_ROTATION_VECTOR`。起動時の姿勢を中立とし、約 20 度で移動量が最大 |
| 平滑化 | 時定数 0.12 秒のローパスフィルター。中立位置も 6 秒かけて現在の姿勢へ追従 |
| 描画頻度 | Choreographer で 60fps に間引き（120Hz・144Hz の画面でも約 60fps） |
| 省電力 | `onVisibilityChanged(false)` でセンサーの登録を解除し、描画ループも停止 |
| 設定 | 奥行きの強さ 0.5〜2 倍（0.01 倍刻み）。変更は即座に反映 |

## ファイル構成

```
app/src/main/
├── AndroidManifest.xml
├── java/com/tak/parallaxwallpaper/
│   ├── ParallaxWallpaperService.kt  壁紙サービスと Engine（描画ループ・表示状態の管理）
│   ├── ParallaxRenderer.kt          画像の読み込み・影の生成・各レイヤーの描画
│   ├── TiltTracker.kt               回転ベクトルセンサーから傾きを算出
│   ├── LowPassFilter.kt             経過時間に基づくローパスフィルター
│   ├── FrameLimiter.kt              60fps への間引き
│   ├── ParallaxMath.kt              移動量・明滅・スライダー変換の計算
│   ├── DepthPrefs.kt                奥行きの強さの保存
│   └── SettingsActivity.kt          設定画面
└── res/
    ├── drawable/  background.png, glow.png, foreground.png（仮画像）, ic_launcher.xml
    ├── layout/activity_settings.xml
    ├── values/strings.xml
    └── xml/wallpaper.xml
app/src/test/java/com/tak/parallaxwallpaper/  単体テスト
```

## 画像の差し替え

`app/src/main/res/drawable/` の 3 枚は動作確認用の仮画像です。同じファイル名で上書きしてください。

- 推奨サイズは端末の画面解像度と同程度（例：1080×2400 以上）。縦横比が異なる場合は中央合わせで切り抜かれます
- `glow.png` と `foreground.png` は透過 PNG にしてください。影は `foreground.png` の不透明部分から作られます
- 前景が画面いっぱいの絵だと影が見えにくくなります。中央に浮かぶ物体のような構図が効果的です
- 画像は端末の画素密度による自動拡大を止めて読み込むため、`drawable` に置いても過剰にメモリを消費しません

## ビルド手順（Android Studio）

1. Android Studio（Koala 2024.1.1 以降を推奨）を起動し、**File → Open** で `parallax-wallpaper` フォルダーを開きます。リポジトリの最上位ではなく、このフォルダーを開いてください
2. 初回は Gradle の同期が自動で始まります。SDK Platform 34 が未導入の場合は、表示される案内から導入します
3. **File → Settings → Build, Execution, Deployment → Build Tools → Gradle** で Gradle JDK が 17 以上になっていることを確認します（Android Studio 同梱の JBR で可）
4. 端末側で開発者向けオプションを有効にし、**USB デバッグ**をオンにします。HyperOS の場合は「USB 経由でインストール」と「USB デバッグ（セキュリティ設定）」もオンにします（Mi アカウントへのログインが必要）
5. 端末を USB で接続し、ツールバーの実行構成 `app` を選んで **Run ▶** を押します。設定画面が起動すればインストール完了です

コマンドラインの場合：

```bash
cd parallax-wallpaper
./gradlew test           # 単体テスト
./gradlew assembleDebug  # app/build/outputs/apk/debug/app-debug.apk が生成される
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 壁紙への設定手順

1. ランチャーの「パララックス壁紙の設定」を開きます
2. スライダーで奥行きの強さを調整します（後から変更可能）
3. 「ライブ壁紙に設定する」を押すと、この壁紙のプレビュー画面が開きます
4. プレビュー画面で「壁紙に設定」（端末により「適用」）を押し、ホーム画面・ロック画面のどちらに設定するかを選びます

ボタンでプレビューが開かない端末では、**設定 → 壁紙 → ライブ壁紙**（HyperOS では **壁紙とテーマ** 内）から「パララックス壁紙」を選んでください。壁紙の選択画面にある「設定」からも設定画面を開けます。

## 検証の状況

- 確認済み：全 Kotlin ファイルを Kotlin 1.9.24 で Android API 34 のフレームワーク（Robolectric の android-all）に対して型検査し、エラーなし。単体テスト 16 件が通過。XML の整形式を確認
- 未確認：Gradle での実ビルド、実機での描画・動作。特に以下は実機で調整が必要になる可能性があります
  - 傾きの向き：左右・上下が想定と逆に感じられる場合は、`TiltTracker.onSensorChanged()` の符号を反転します
  - 横画面時の軸の入れ替え（`TiltTracker` の `ROTATION_90` / `ROTATION_270`）
  - 影の濃さ・ぼかし・ずれ量（`ParallaxRenderer` の `SHADOW_*` 定数）
  - 反応の滑らかさ（`ParallaxWallpaperService` の `SMOOTHING_SEC`、`MAX_TILT_RAD`）
