# AJO In-App Message 実装ガイド（統合ドキュメント）

Adobe Journey Optimizer (AJO) の In-App Message について、ボタン押下挙動・Unity/Android の Activity 設定・実装チェック・事実確認を一括でまとめたドキュメントです。

**参照**: [In-App Messaging - Adobe Developer](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/in-app-message/)

---

## 目次

1. [ボタン押下挙動](#1-ボタン押下挙動)
2. [Unity / Android の Activity 設定](#2-unity--android-の-activity-設定)
3. [実装ガイドとの照合・チェックリスト](#3-実装ガイドとの照合チェックリスト)
4. [事実確認・windowIsTranslucent](#4-事実確認windowistranslucent)

---

## 1. ボタン押下挙動

### 1.0 In-App Message 配信のための必須作業

- **Data Stream**: Adobe Experience Platform Data Collection で Data Stream を定義。AEP Edge / AJO を有効化。AJO Push Tracking Experience Event Dataset を利用。マージポリシーで Active-On-Edge を有効化。
- **Data Collection Tags**: AEP Edge Network 拡張、Lifecycle Application Foreground/Background の Rule。
- **Schema**: Experience Event スキーマに Lifecycle 用 Field group を追加。
- **アプリ**: Lifecycle 拡張の導入。`MobileCore.initialize` の場合は追加コード不要。`registerExtensions` の場合は `lifecycleStart` / `lifecyclePause` を適切に呼ぶ。

### 1.1 全体の流れ（ボタン押下からリンクが開くまで）

- メッセージは **WKWebView** で表示。ボタン（リンク）タップ → SDK が URL を解釈 → `adbinapp://` ならメッセージを閉じる／インタラクション送信／**link** を OS API で開く（ブラウザ or ディープリンク）。アプリが WKNavigationDelegate でインターセプトした場合は、ナビゲーションをキャンセルしてアプリ内 WebView で開く等が可能。

### 1.2 AJO Campaign で設定できること

- **標準 UI**: ボタン URL は常に `adbinapp://dismiss?interaction=...&link=...` 形式。編集できるのは **interaction** と **link** のみ。
- **カスタム HTML**: 編集できる場合のみ、リンク URL を自由に書ける（`adbinapp://` 以外や postMessage 等）。

### 1.3 SDK の標準の動き

- **SDK**: メッセージ内 WKWebView で `adbinapp://` へのナビゲーションを検知し、メッセージを閉じる・インタラクション送信・**link の URL を OS に開かせる**まで行う。
- **アプリ**: `adbinapp://` は `application:openURL` には渡らない。標準のままなら、ディープリンク用 URL スキームを登録しておくだけ。

**adbinapp のパラメータ**: ホスト `dismiss` = メッセージを閉じる。`interaction=○○` = Edge に propositionInteract 送信。`link=○○` = その URL を OS に開かせる。

### 1.4 アプリ側の選択肢（3つの方針）

| 方針 | いつ選ぶか | アプリ側の実装 |
|------|------------|----------------|
| **A. 標準のまま** | link をブラウザ or ディープリンクで開けばよい。 | 不要（AJO の設定のみ）。 |
| **B. アプリ内 WebView** | link の先をアプリ内 WebView で表示したい。 | MessagingDelegate で WKWebView のナビゲーションをインターセプト。 |
| **C. JS 連携で Unity に通知** | カスタム HTML からボタンごとの値を Unity に渡したい。 | MessagingDelegate で handleJavascriptMessage を登録し、HTML で postMessage。 |

- **方法 A**: AJO で interaction と link を設定するだけ。ディープリンクを使う場合のみ URL スキームを登録。
- **方法 B**: shouldShowMessage 内で Message の view（WKWebView）に WKNavigationDelegate を設定。`adbinapp://...&link=...` をキャンセルし、link をアプリ内 WebView で開く。message.dismiss() と message.track(interaction, .interact) を呼ぶ。
- **方法 C**: shouldShowMessage 内で `message.handleJavascriptMessage("AEPInAppCallback", handler)` を登録。ハンドラで `message.track(...)` と `UnitySendMessage("AEPManager", "OnInAppMessageAction", payload)`。HTML では `webkit.messageHandlers.AEPInAppCallback.postMessage(action)`。Android では `window.AEPInAppCallback` の有無で分岐（`window.AEPInAppCallback.postMessage(action)` または webkit.messageHandlers）。

**Android で interaction=webview がアプリ内 WebView で開かない場合**: Message が InternalMessage で getView/getWebView がなく、WebView に WebViewClient を設定できないことが原因。AEPSdkBridge で Presentable/Message から getWindow()→getDecorView() で View を取得するパターンや、DecorView 内で In-App と推測できる WebView を優先するロジックを追加する。logcat の「no View from Message or Presentable」「findInAppWebViewFromActivity」を確認する。

**参考リンク**: [Handle URL clicks](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/in-app-message/tutorials/handle-clicks/)、[MessagingDelegate](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/in-app-message/tutorials/messaging-delegate/)、[Call native from JavaScript](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/in-app-message/tutorials/native-from-javascript/)。

---

## 2. Unity / Android の Activity 設定

**結論**: **Legacy Activity（UnityPlayerActivity）では In-App Message が表示されない。** 推奨は **GameActivity（UnityPlayerGameActivity）** を利用すること。

### 2.1 原因

- **Legacy**: `UnityPlayer` の `applySurfaceViewSettings(SurfaceView)` で、**isWindowTranslucent() が true のときに** `SurfaceView.setZOrderOnTop(true)` を呼ぶ。その結果、content に addView したオーバーレイは背面に隠れる。
- **GameActivity**: Google の GameActivity は SurfaceView に setZOrderOnTop を呼ばないため、オーバーレイが前面に表示される。

**設定場所**: Unity Editor → Edit → Project Settings → Player → Android → Configuration → Application Entry Point で **GameActivity** を選択。

**Legacy のまま使う場合**: テーマで `android:windowIsTranslucent` を false にする。ビルド後に生成された `unityLibrary/.../res/values/styles.xml` の `UnityThemeSelector` に `<item name="android:windowIsTranslucent">false</item>` を追加（ビルドのたびに必要）。または Custom Main Manifest とカスタムテーマで上書き。

### 2.2 「Current activity is null」で表示されない場合

- **原因**: Unity では初期化が Activity の onResume **後**に走ることが多く、SDK が ActivityLifecycleCallbacks で一度も onActivityResumed を受け取っておらず、current activity が null のままになる。
- **対策**: 初期化コールバック内で `setCurrentActivityForInApp(UnityPlayer.currentActivity)` を呼ぶ、および `registerActivityLifecycleForInApp(Application)` で onActivityResumed のたびに同様に渡す。**推奨・根本対策**: カスタム Application（AEPApplication）で `Application.onCreate()` から `MobileCore.initialize()` を実行する（次項）。

### 2.3 カスタム Application で「Current activity is null」を根本解消（推奨）

- **AEPApplication.java**: `Application.onCreate()` で `StreamingAssets/AEPAppId.txt` を読み、`MobileCore.initialize(this, appId, callback)` を実行。コールバックで `AEPSdkBridge.onSdkInitializedByApplication(this)` を呼び、デリゲート登録と ActivityLifecycleCallbacks 登録を行う。
- **有効化**: Custom Main Manifest を有効にし、`<application>` に `android:name="com.adobe.aep.unity.AEPApplication"` を追加。`Assets/StreamingAssets/AEPAppId.txt` に Launch App ID を記入。
- **確認**: logcat で `AEPApplication` タグ。「AEP SDK initialization completed (by AEPApplication.onCreate).」が出ていれば In-App 表示の可能性が高い。

### 2.4 その他の確認事項

- **トリガーが application.click の場合**: アプリ側でそのイベントを送る必要あり（本プロジェクトでは SendEvent 等で `Edge.sendEvent("application.click", ...)` を送信）。
- **general.callback.timeout**: ネットが遅い環境では実機・Wi‑Fi で試す、または AJO のトリガー条件を見直す。
- **初回表示で数秒止まる**: Android の WebView 初回初期化がメインスレッドで行われるため。2回目以降は比較的速い。仕様として理解し、必要ならローディング表示を検討。
- **表示中に Unity のアニメーションが止まる**: Android で In-App が別ウィンドウ/ダイアログで出るため、Unity が一時停止とみなす。Run In Background を有効にすると止まらない可能性があるが、任意。

---

## 3. 実装ガイドとの照合・チェックリスト

[Adobe 公式: In-App Messaging](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/in-app-message/) および [MessagingDelegate](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/in-app-message/tutorials/messaging-delegate/) に基づく確認。

### 公式で求められていること

- **PresentationDelegate の登録**: ✅ 実施済み。`ServiceProvider.getInstance().getUIService().setPresentationDelegate(proxy)` をリフレクションで実行。
- **canShow**: ✅ 実施済み。proxy で常に `true` を返す。

### Android で必要なこと（公式に書いていない）

- SDK は「現在の Activity」の content にビューを addView する。**現在の Activity が null だと表示できない**（logcat: `Current activity is null. Cannot show presentable.`）。
- Unity では `MobileCore.initialize()` が onResume **後**になるため、SDK が current activity を一度も受け取らず null のままになる。**対策**: カスタム Application で `Application.onCreate()` から `MobileCore.initialize()` を実行する。

### チェックリスト

| # | 項目 | 本プロジェクト |
|---|------|----------------|
| 1 | PresentationDelegate の登録 | ✅ 実施 |
| 2 | canShow で true を返す | ✅ 実施 |
| 3 | MobileCore.initialize(Application) | ✅ 実施（呼び出しが Unity 起動後のため遅い） |
| 4 | SDK が「現在の Activity」を取得できるタイミング | ❌ 不備 → カスタム Application で解消 |
| 5 | GameActivity 使用（または windowIsTranslucent=false） | 要確認（Legacy + translucent だとオーバーレイが背面に隠れる） |

**結論**: 不備は主に #4（初期化タイミング）。カスタム Application（AEPApplication）で `onCreate()` から `MobileCore.initialize()` を呼ぶ実装で解消する。

---

## 4. 事実確認・windowIsTranslucent

### (1) 「windowIsTranslucent が true の時に Legacy を使っていると In-App が表示されない」

**結論: 事実です。**

- Legacy の UnityPlayer が `isWindowTranslucent()` が true のときだけ `SurfaceView.setZOrderOnTop(true)` を呼ぶ（Unity 6000.3 の classes.jar で確認）。
- setZOrderOnTop(true) の SurfaceView はウィンドウ最前面になり、他ビューは背後に回る（Android 公式ドキュメント・Stack Overflow）。

### (2) 「GameActivity では windowIsTranslucent が true でも AppCompat の働きによって表示される」

**結論: 「表示される」は事実。「AppCompat の働きによって」は根拠なし。**

- GameActivity（Google）は content に載せる SurfaceView に **setZOrderOnTop を呼ばない**（ソースで確認）。そのためオーバーレイは背面に回らず表示される。
- 「AppCompat の働きによって」という説明は今回の調査では裏付けていない。

### 現在のプロジェクトでの windowIsTranslucent

- マニフェストでは `BaseUnityGameActivityTheme` を参照。このスタイルに `android:windowIsTranslucent` は書かれていない。
- 解決値は親テーマ（Theme.AppCompat.Light.NoActionBar）に依存し、通常は **false（不透明）**。
- Unity の規定では、.Translucent サブスタイルを使わない限り windowIsTranslucent は指定されず、多くの場合 false になる。
