# README.md 技術的精査レポート

実施日: 2025-02-09  
根拠: Unity 公式ドキュメント、Adobe AEP/AJO 公式ドキュメント、Web 検索による裏付け。

---

## 参照した URL

| 区分 | 説明 | URL |
|------|------|-----|
| Unity | Create a native plug-in for iOS | https://docs.unity3d.com/Manual/ios-native-plugin-create.html |
| Unity | Building plug-ins for iOS | https://docs.unity3d.com/2023.1/Documentation/Manual/PluginsForIOS.html |
| Unity | Callback from native code | https://docs.unity3d.com/6000.2/Documentation/Manual/ios-native-plugin-call-back.html |
| Adobe | Mobile Core API Reference | https://developer.adobe.com/client-sdks/home/base/mobile-core/api-reference/ |
| Adobe | Code-based Experiences & Content Cards - API Reference | https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/code-based/api-reference/ |
| Adobe | Fetch and Display Content Cards (iOS) | https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/content-card-ui/iOS/tutorial/displaying-content-cards/ |
| Adobe | Surface - Public classes | https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/public-classes/surface |
| Adobe | PropositionItem - Public classes | https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/public-classes/proposition-item |
| Adobe | Assurance | https://developer.adobe.com/client-sdks/home/base/assurance |
| Adobe | Customizing Content Card templates (iOS) | https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/content-card-ui/iOS/tutorial/customizing-content-card-templates/ |
| Experience League | Content cards (AJO) | https://experienceleague.adobe.com/en/docs/journey-optimizer/using/channels/content-card/configure/content-card-configuration |

※ 本レポート内の「裏付け」は、特記しない限り上記のいずれかに基づく。

---

## 1. 正しいと判断した記述（裏付けあり）

### Unity iOS ネイティブプラグイン

| 記述 | 裏付け |
|------|--------|
| C ABI の関数を `DllImport("__Internal")` で呼ぶ形式のみサポート | [Unity Manual - Create a native plug-in for iOS](https://docs.unity3d.com/Manual/ios-native-plugin-create.html): iOS ではプラグインが静的リンクされるため `__Internal` を指定。C/C++ の場合は `extern "C"` で C リンケージが必要。 |
| Swift/ObjC を直接呼べないため C の入り口が必要 | 同上。C# からは `[DllImport("__Internal")]` で extern な C 関数を呼ぶ形式のみ。 |
| ネイティブ → Unity の戻りは **UnitySendMessage(objectName, methodName, message)** のみ | [Unity - Callback from native code](https://docs.unity3d.com/Documentation/Manual/ios-native-plugin-call-back.html): ネイティブから C# を呼ぶ方法として `UnitySendMessage` が案内。メソッドは `void MethodName(string message)` のシグネチャに限定。 |

### .mm と Swift の 2 段構成

- `.mm` で `extern "C"` により C リンケージの関数を定義し、その中で Swift の `AEPSdkBridge` を呼ぶ記述は、Unity 公式の「C の入り口」の説明と一致。
- Swift を ObjC から呼ぶために `@objc` と `UnityFramework-Swift.h` を使う構成は、一般的な Unity iOS + Swift ブリッジのやり方と一致。

### AEP SDK（Adobe 公式）

| 記述 | 裏付け |
|------|--------|
| **MobileCore.initialize(appId:...)** | [Adobe Mobile Core API](https://developer.adobe.com/client-sdks/home/base/mobile-core/api-reference/) に言及。簡易初期化 API として存在。本プロジェクトのコードでも `MobileCore.initialize(appId: "…") { ... }` を使用。 |
| **Messaging.updatePropositionsForSurfaces([surface])** | [Code-based API Reference](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/code-based/api-reference/): `updatePropositionsForSurfaces` で Proposition を取得してキャッシュ。iOS では **updatePropositionsForSurfacesWithCompletionHandler** として完了コールバック付きオーバーロードが存在し、本プロジェクトはそれを利用。 |
| **Messaging.getPropositionsForSurfaces([surface])** | 同上。キャッシュ済み Proposition を取得する API として記載。 |
| **Messaging.getContentCardsUI(for:customizer:listener:)** | [Fetch and Display Content Cards](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/content-card-ui/iOS/tutorial/displaying-content-cards/): `getContentCardsUI` で `ContentCardUI` の配列を取得。Swift では `getContentCardsUI(for: surface)` および customizer/listener 付きオーバーロードが利用可能。`ContentCardUI.view` で SwiftUI ビューを取得して表示する例を記載。 |
| **Surface(path:)** | [Surface - Public classes](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/public-classes/surface): `Surface(path: String)` でインスタンス作成。内部で `mobileapp://` + バンドル ID + path の URI になる。 |
| **ContentCardSchemaData** / **contentCardSchemaData** | [PropositionItem](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/public-classes/proposition-item): `PropositionItem` に `contentCardSchemaData` があり、`ContentCardSchemaData` を返す。スキーマが `.contentCard` の場合に利用。 |
| **ContentCardSchemaData.track(withEdgeEventType: .display)** | 実装コードで使用。Content Card の表示トラッキング用。deprecated な `ContentCard` ではなく `ContentCardSchemaData` の track を使用している点は現行 API に沿っている。 |
| **Assurance 手動起動** | 本プロジェクトは `startAssuranceSession()` というラッパーを定義。Adobe 公式は `Assurance.startSession(url: URL?)` をデバッグ用に案内。README の「手動起動」の説明は妥当。 |

### AJO / Content Cards 用語

| 用語 | README の説明 | 裏付け |
|------|----------------|--------|
| **Surface** | 配信場所を識別するパス（例: "square"）。AJO で設定した Surface と一致させる。 | Experience League: Surface は `mobileapp://bundle/path` 等形式の URI で識別。iOS では `Surface(path:)` の path 部分がそれに相当。 |
| **Proposition** | Surface に紐づく「どのカードを出すか」の情報。SDK がキャッシュし、getPropositionsForSurfaces / getContentCardsUI で取得。 | 公式: updatePropositionsForSurfaces で取得してキャッシュ、getPropositionsForSurfaces でキャッシュ取得。getContentCardsUI は「表示用 UI オブジェクト」の取得なので、厳密には Proposition そのものは getPropositionsForSurfaces で取得。 |
| **Content Card** | 1 枚分のカードデータ（ContentCardSchemaData）。AJO の title/body/image/buttons 等のネスト構造。 | PropositionItem の contentCardSchemaData で取得。AJO のネスト構造（title.content, body.content 等）の記述は実装と一致。 |
| **テンプレート (Large / Small / ImageOnly)** | getContentCardsUI でビュー生成。AJO の施策でテンプレートが決まる。 | 公式チュートリアルで LargeImageTemplate / SmallImageTemplate / ImageOnlyTemplate および ContentCardCustomizing が言及。 |

---

## 2. 修正・補足を推奨する点

### 2.1 Proposition の「取得」表現（軽微）

- **現状**: 「Proposition: … SDK がキャッシュし、`getPropositionsForSurfaces` / `getContentCardsUI` で取得」
- **指摘**: 厳密には **Proposition の実体**は `getPropositionsForSurfaces` で取得する。`getContentCardsUI` は「その Surface に紐づく Content Card の**表示用 UI オブジェクト**」を返すため、用語上「Proposition を取得」より「Content Card の表示用データを取得」に近い。
- **推奨**: 「`getPropositionsForSurfaces` で取得。ネイティブテンプレート表示には `getContentCardsUI` で ContentCardUI を取得。」のように役割を分けて書くとより正確。

### 2.2 updatePropositionsForSurfaces の「完了」の仕様

- **現状**: シーケンス図で「AEP コールバック(success/failed)」「15秒タイムアウト」と記載。
- **裏付け**: 公式 API Reference では **updatePropositionsForSurfacesWithCompletionHandler** として、完了時に `completion(Bool)` が呼ばれるオーバーロードが存在。本プロジェクトの Swift コードはそれを使用している。
- **補足推奨**: 「完了は Messaging 拡張の updatePropositionsForSurfaces の完了ハンドラ（iOS では updatePropositionsForSurfacesWithCompletionHandler 相当）で検知」と一文あると、公式用語と対応が明確になる。

### 2.3 Surface の URI 形式（補足として有益）

- **現状**: Surface を「パス（例: "square"）」と説明。
- **補足**: 公式では Surface は **URI** で識別され、iOS の `Surface(path: "square")` は内部で `mobileapp://<bundleId>/square` のような URI になる。AJO 側で設定する Surface 識別子と、この path（または URI）を一致させる必要がある、と README に一言あるとよい。

### 2.4 Assurance の実際の API 名（README はプロジェクト用で問題なし）

- 本プロジェクトは `startAssuranceSession()` というラッパーを定義しており、README の「Assurance 手動起動」はこのメソッドを指している。
- Adobe 公式の API 名は `Assurance.startSession(url: URL?)`。README はプロジェクトの公開 API を説明しているため、現状のままでよい。

---

## 3. 誤り・矛盾はないと判断した点

- **UnitySendMessage の 1 フレーム遅延**: README では触れていないが、公式には「非同期で 1 フレーム遅延」とある。実装で考慮していれば問題なし。必要なら README の「ネイティブ → Unity」の節に一言追記可能。
- **getContentCardsUI のシグネチャ**: README の `getContentCardsUI(for:customizer:listener:)` は、実装の `getContentCardsUI(for: surface, customizer: customizer, listener: listener) { result in ... }` と一致。完了ハンドラは省略表記で問題なし。
- **MobileCore.initialize**: 公式で言及されている簡易初期化 API。コールバックで完了を検知する記述は実装と一致。

---

## 4. 総合評価

- **Unity iOS ブリッジ（C / .mm / Swift / UnitySendMessage）**: 公式仕様と一致しており、技術的に正確。
- **AEP SDK（MobileCore, Messaging, Surface, Proposition, ContentCardUI, ContentCardSchemaData）**: 公式 API および用語と一致。実装も README の説明と整合している。
- **AJO Content Cards の用語とフロー**: 公式の Surface / Proposition / Content Card / テンプレートの説明と矛盾なし。Proposition の「取得」の表現だけ、上記のとおり役割を分けるとより正確になる。

**結論**: README の内容はおおむね技術的に正しい。修正は「Proposition の取得」の表現の明確化と、Surface/updatePropositions の公式用語の補足程度で十分と考えられる。
