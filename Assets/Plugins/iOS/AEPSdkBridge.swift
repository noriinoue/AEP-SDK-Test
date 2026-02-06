import Foundation
import SwiftUI
import AEPCore
import AEPAssurance
import AEPEdge
import AEPEdgeIdentity
import AEPMessaging

// Objective-C側で定義されたヘルパー関数の宣言
@_silgen_name("_unity_get_view_controller")
func unityGetViewController() -> UIViewController?

// UnityへのメッセージSend関数
@_silgen_name("UnitySendMessage")
func UnitySendMessage(_ obj: UnsafePointer<CChar>, _ method: UnsafePointer<CChar>, _ msg: UnsafePointer<CChar>)

private func sendToUnity(objectName: String, method: String, message: String) {
    objectName.withCString { o in
        method.withCString { m in
            message.withCString { msg in
                UnitySendMessage(o, m, msg)
            }
        }
    }
}

@objc public class AEPSdkBridge: NSObject {
    // MARK: - 待機時間・タイムアウト定数（秒）
    /// SDK初期化後のプリフェッチ開始までの遅延
    private static let prefetchDelayAfterSDKInit: TimeInterval = 2.0
    /// 手動Proposition更新のタイムアウト
    private static let manualPropositionUpdateTimeout: TimeInterval = 15.0
    /// ローディングdismiss完了後、次の処理までの待機
    private static let loadingDismissCompletionDelay: TimeInterval = 0.5
    
    private static var isInitialized = false
    private static var initializationCallbacks: [(Bool) -> Void] = []
    
    // 非同期でSDKを初期化（Assuranceは自動起動しない）
    @objc(setupSDKWithCallback:)
    public static func setupSDK(callback: @escaping (Bool) -> Void) {
        // 既に初期化済みの場合は即座にコールバック
        if isInitialized {
            DispatchQueue.main.async {
                callback(true)
            }
            return
        }
        
        // コールバックをキューに追加
        initializationCallbacks.append(callback)
        
        // 初回のみ初期化を実行
        if initializationCallbacks.count > 1 {
            return
        }
        
        let startTime = Date()
        print("AEP SDK initialization started (async)")
        
        MobileCore.setLogLevel(.debug)
        
        // MobileCore.initialize のコールバックで初期化完了を検知（wait 処理を使わない）
        // https://developer.adobe.com/client-sdks/home/base/mobile-core/api-reference/#initialize
        MobileCore.initialize(appId: "6a203c8a0ff8/0d1cfe126ee2/launch-8e810d80e4b7-development") {
            let elapsedTime = Date().timeIntervalSince(startTime)
            
            DispatchQueue.main.async {
                isInitialized = true
                print("AEP SDK initialization completed in \(String(format: "%.2f", elapsedTime))s")
                print("SDK will continue loading configurations in background")
                
                for cb in initializationCallbacks {
                    cb(true)
                }
                initializationCallbacks.removeAll()
                
                DispatchQueue.main.asyncAfter(deadline: .now() + prefetchDelayAfterSDKInit) {
                    prefetchContentCards()
                }
            }
        }
    }
    
    // コンテンツカードを事前取得（起動時にバックグラウンドで実行）
    private static func prefetchContentCards() {
        let surface = Surface(path: "square")
        
        print("Prefetching content cards for surface: square")
        Messaging.updatePropositionsForSurfaces([surface]) { success in
            if success {
                print("Content cards prefetched successfully")
                
                DispatchQueue.main.async {
                    sendToUnity(objectName: "AEPManager", method: "OnContentCardsPrefetched", message: "success")
                }
            } else {
                print("Failed to prefetch content cards")
            }
        }
    }
    
    // Assuranceセッションを手動で開始（デバッグ時のみ使用）
    // 非同期で実行されるため、WebViewの起動でメインスレッドをブロックしない
    @objc public static func startAssuranceSession() {
        guard isInitialized else {
            print("AEP SDK not initialized yet. Call setupSDK first.")
            return
        }
        // Assurance.startSession() は必要に応じてここで呼ぶ
    }

    @objc public static func sendEvent(_ eventName: String, jsonData: String) {
        var xdmData: [String: Any] = [:]
        xdmData["eventType"] = eventName
        
        // JSON文字列をパースしてDictionaryに変換
        if let data = jsonData.data(using: .utf8),
           let json = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] {
            xdmData.merge(json) { (current, _) in current }
        }
        
        let experienceEvent = ExperienceEvent(xdm: xdmData)
        Edge.sendEvent(experienceEvent: experienceEvent)
    }
    
    @objc public static func updateIdentities(_ identifierType: String, identifier: String) {
        let identityMap = IdentityMap()
        identityMap.add(item: IdentityItem(id: identifier), withNamespace: identifierType)
        Identity.updateIdentities(with: identityMap)
    }
    
    // 手動でPropositionを更新（Unity C#から呼び出し、完了通知付き）
    @objc public static func updatePropositionsManually(_ surfacePath: String) {
        print("Manual proposition update requested for surface: \(surfacePath)")
        
        // ローディング表示
        showLoadingOverlay()
        
        let surface = Surface(path: surfacePath)
        var isCompleted = false
        
        // タイムアウト設定（15秒）
        DispatchQueue.main.asyncAfter(deadline: .now() + manualPropositionUpdateTimeout) {
            if !isCompleted {
                print("Manual proposition update timed out")
                dismissLoadingOverlay()
                
                sendToUnity(objectName: "AEPManager", method: "OnPropositionsUpdated", message: "timeout:\(surfacePath)")
            }
        }
        
        Messaging.updatePropositionsForSurfaces([surface]) { success in
            DispatchQueue.main.async {
                guard !isCompleted else {
                    print("Manual proposition update completion called after timeout")
                    return
                }
                
                isCompleted = true
                
                // ローディングを閉じる
                dismissLoadingOverlay()
                
                if success {
                    print("Manual proposition update succeeded")
                    sendToUnity(objectName: "AEPManager", method: "OnPropositionsUpdated", message: "success:\(surfacePath)")
                } else {
                    print("Manual proposition update failed")
                    sendToUnity(objectName: "AEPManager", method: "OnPropositionsUpdated", message: "failed:\(surfacePath)")
                }
            }
        }
    }
    
    private static var loadingHostingController: UIViewController?
    private static var errorHostingController: UIViewController?
    
    // ローディング表示（手動Proposition更新等で使用）
    private static func showLoadingOverlay() {
        DispatchQueue.main.async {
            guard let unityViewController = unityGetViewController() else {
                print("ERROR: Cannot show loading overlay - Unity VC not found")
                return
            }
            
            let loadingView = AnyView(
                ZStack {
                    Color.black.opacity(0.5)
                        .edgesIgnoringSafeArea(.all)
                    
                    VStack(spacing: 20) {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            .scaleEffect(2.0)
                        
                        Text("Loading...")
                            .foregroundColor(.white)
                            .font(.headline)
                    }
                }
            )
            
            let hostingController = UIHostingController(rootView: loadingView)
            hostingController.view.backgroundColor = .clear
            hostingController.modalPresentationStyle = .overFullScreen
            hostingController.modalTransitionStyle = .crossDissolve
            
            loadingHostingController = hostingController
            unityViewController.present(hostingController, animated: false) {
                print("Loading overlay displayed")
            }
        }
    }
    
    // ローディングを閉じる（必ずメインスレッドで実行）
    private static func dismissLoadingOverlay(completion: (() -> Void)? = nil) {
        DispatchQueue.main.async {
            guard let controller = loadingHostingController else {
                print("No loading overlay to dismiss")
                completion?()
                return
            }
            
            guard controller.presentingViewController != nil else {
                print("Loading overlay not presented, cleaning up")
                loadingHostingController = nil
                completion?()
                return
            }
            
            print("Dismissing loading overlay")
            controller.dismiss(animated: false) {
                loadingHostingController = nil
                print("Loading overlay dismissed")
                
                // 重要: ViewControllerが完全に解放され、UIの状態が安定するまで待機
                // この待機により、次のpresentが確実に成功する
                DispatchQueue.main.asyncAfter(deadline: .now() + loadingDismissCompletionDelay) {
                    print("Loading overlay cleanup completed, executing completion")
                    completion?()
                }
            }
        }
    }
    
    // エラーメッセージを表示
    private static func showErrorMessage(_ message: String) {
        let errorView = AnyView(
            ZStack {
                Color.black.opacity(0.5)
                    .edgesIgnoringSafeArea(.all)
                
                VStack(spacing: 20) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 50))
                        .foregroundColor(.yellow)
                    
                    Text(message)
                        .foregroundColor(.white)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                        .padding()
                    
                    Button(action: {
                        dismissErrorMessage()
                    }) {
                        Text("OK")
                            .foregroundColor(.white)
                            .padding(.horizontal, 40)
                            .padding(.vertical, 12)
                            .background(Color.blue)
                            .cornerRadius(10)
                    }
                }
                .padding()
            }
        )
        
        let hostingController = UIHostingController(rootView: errorView)
        hostingController.view.backgroundColor = .clear
        hostingController.modalPresentationStyle = .overFullScreen
        hostingController.modalTransitionStyle = .crossDissolve
        
        if let unityViewController = unityGetViewController() {
            errorHostingController = hostingController
            unityViewController.present(hostingController, animated: true, completion: nil)
        }
    }
    
    // エラーメッセージを閉じる
    private static func dismissErrorMessage() {
        guard let controller = errorHostingController else {
            return
        }
        
        guard controller.presentingViewController != nil else {
            errorHostingController = nil
            return
        }
        
        controller.dismiss(animated: true) {
            errorHostingController = nil
        }
    }
    
    // MARK: - Content Cards with Template (SwiftUI ScrollView)
    // 公式デモと同様に getContentCardsUI(for:customizer:listener:) でテンプレートを適用し、
    // ScrollView に card.view を表示する
    
    private static var contentCardsTemplateHostingController: UIViewController?
    
    /// テンプレート付き SwiftUI ScrollView でコンテンツカードを表示（Unity から呼び出し）
    /// - Parameters:
    ///   - surfacePath: Surface パス（例: "square"）
    ///   - templateStyle: "large" または "small"（未指定時は "large"）
    @objc(showContentCardsSwiftUIWithTemplates:templateStyle:)
    public static func showContentCardsSwiftUIWithTemplates(_ surfacePath: String, templateStyle: String) {
        DispatchQueue.main.async {
            guard let unityViewController = unityGetViewController() else {
                print("ERROR: Cannot show content cards - Unity VC not found")
                return
            }
            
            let surface = Surface(path: surfacePath)
            let view = ContentCardsSwiftUIView(
                surfacePath: surfacePath,
                surface: surface,
                templateStyle: templateStyle,
                onClose: {
                    dismissContentCardsTemplateView()
                }
            )
            
            let hostingController = UIHostingController(rootView: view)
            hostingController.view.backgroundColor = .systemBackground
            hostingController.modalPresentationStyle = .pageSheet
            
            contentCardsTemplateHostingController = hostingController
            unityViewController.present(hostingController, animated: true) {
                print("Content Cards (Template) SwiftUI presented")
            }
        }
    }
    
    private static func dismissContentCardsTemplateView() {
        DispatchQueue.main.async {
            guard let controller = contentCardsTemplateHostingController,
                  controller.presentingViewController != nil else {
                contentCardsTemplateHostingController = nil
                return
            }
            controller.dismiss(animated: true) {
                contentCardsTemplateHostingController = nil
                print("Content Cards (Template) SwiftUI dismissed")
            }
        }
    }
    
    // コンテンツカードを取得してコールバックで返す（Objective-Cから呼ばれる）
    @objc(getContentCardsForUnity:callback:)
    public static func getContentCardsForUnity(_ surfacePath: String, callback: @escaping (String) -> Void) {
        let surface = Surface(path: surfacePath)
        
        // AJO SDKからプロポジションを取得（キャッシュから）
        Messaging.getPropositionsForSurfaces([surface]) { propositionsDict, error in
            DispatchQueue.main.async {
                var jsonResult: String
                
                if let error = error {
                    // エラー時
                    print("Failed to get content cards for Unity: \(error.localizedDescription)")
                    jsonResult = "{\"error\":\"\(error.localizedDescription)\"}"
                } else if let propositions = propositionsDict?[surface], !propositions.isEmpty {
                    // AJO SDK標準のContentCardSchemaDataを使ってパース（非推奨APIを使わない）
                    var cardsArray: [[String: Any]] = []
                    
                    for proposition in propositions {
                        for item in proposition.items {
                            // Content Cardかチェック
                            if item.schema == .contentCard {
                                // ContentCardSchemaDataを取得
                                if let contentCardSchemaData = item.contentCardSchemaData {
                                    
                                    // contentプロパティをデバッグ出力
                                    if let contentDict = contentCardSchemaData.content as? [String: Any] {
                                        // AJOの実データはネスト構造: title.content, body.content, image.url, buttons[0].actionUrl 等
                                        var cardData: [String: Any] = [:]
                                        
                                        if let titleObj = contentDict["title"] as? [String: Any],
                                           let title = titleObj["content"] as? String, !title.isEmpty {
                                            cardData["title"] = title
                                        }
                                        if let bodyObj = contentDict["body"] as? [String: Any],
                                           let body = bodyObj["content"] as? String {
                                            cardData["body"] = body
                                        }
                                        if let imageObj = contentDict["image"] as? [String: Any],
                                           let imageUrl = imageObj["url"] as? String, !imageUrl.isEmpty {
                                            cardData["imageUrl"] = imageUrl
                                        }
                                        // トップレベル actionUrl または buttons[0].actionUrl
                                        var actionUrl: String?
                                        if let top = contentDict["actionUrl"] as? String, !top.isEmpty {
                                            actionUrl = top
                                        }
                                        if actionUrl == nil, let buttons = contentDict["buttons"] as? [[String: Any]],
                                           let first = buttons.first,
                                           let url = first["actionUrl"] as? String, !url.isEmpty {
                                            actionUrl = url
                                        }
                                        if let url = actionUrl { cardData["actionUrl"] = url }
                                        
                                        // ボタンラベル（buttons[0].text.content）
                                        if let buttons = contentDict["buttons"] as? [[String: Any]],
                                           let first = buttons.first,
                                           let textObj = first["text"] as? [String: Any],
                                           let textContent = textObj["content"] as? String, !textContent.isEmpty {
                                            cardData["buttonText"] = textContent
                                        }
                                        
                                        if !cardData.isEmpty {
                                            cardsArray.append(cardData)
                                        }
                                        
                                        // 表示トラッキング（AJO SDK標準方式 - ContentCardSchemaDataを使用）
                                        contentCardSchemaData.track(withEdgeEventType: .display)
                                    } else {
                                        print("Failed to cast content to [String: Any] for item: \(item.itemId)")
                                    }
                                }
                            }
                        }
                    }
                    
                    // JSON文字列に変換
                    if let jsonData = try? JSONSerialization.data(withJSONObject: ["cards": cardsArray], options: []),
                       let jsonString = String(data: jsonData, encoding: .utf8) {
                        jsonResult = jsonString
                    } else {
                        jsonResult = "{\"error\":\"Failed to serialize content cards\"}"
                        print("Failed to serialize content cards to JSON")
                    }
                } else {
                    // Propositionが見つからない
                    print("No content cards available for Unity text display")
                    jsonResult = "{\"cards\":[]}"
                }
                
                // コールバックを呼び出してJSONを返す
                callback(jsonResult)
            }
        }
    }

}

// MARK: - Content Cards SwiftUI (MessagingDemoAppSwiftUI CardsView に準拠)
// https://github.com/adobe/aepsdk-messaging-ios/blob/main/TestApps/MessagingDemoAppSwiftUI/AppPages/CardsView.swift

/// テンプレート適用済みカードを ScrollView に表示（デモと同様に LargeImage / SmallImage / ImageOnly を 1 つの Customizer でスタイル）
struct ContentCardsSwiftUIView: View {
    let surfacePath: String
    let surface: Surface
    let templateStyle: String
    let onClose: () -> Void
    
    @StateObject private var viewModel = ContentCardsTemplateViewModel()
    private let customizer = ContentCardCustomizerForBridge()
    
    var body: some View {
        NavigationView {
            ZStack {
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVStack(spacing: 20) {
                        ForEach(viewModel.cards) { card in
                            card.view
                                .overlay(
                                    RoundedRectangle(cornerRadius: 5)
                                        .stroke(Color(.systemGray3), lineWidth: 1)
                                )
                                .padding()
                        }
                    }
                }
                
                if viewModel.showLoading {
                    ProgressView("Loading...")
                        .progressViewStyle(CircularProgressViewStyle())
                        .padding()
                        .background(Color.white.opacity(0.8))
                        .cornerRadius(10)
                        .shadow(radius: 10)
                }
                
                if let error = viewModel.loadError {
                    VStack(spacing: 12) {
                        Text(error)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                            .padding()
                    }
                }
            }
            .navigationTitle("Content Cards")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Close") {
                        onClose()
                    }
                }
            }
            .onAppear {
                viewModel.loadCards(surface: surface, customizer: customizer)
            }
        }
    }
}

/// テンプレートビュー用の状態保持（listener を安定して保持）
final class ContentCardsTemplateViewModel: ObservableObject {
    let listener = ContentCardListenerForBridge()
    @Published var cards: [ContentCardUI] = []
    @Published var showLoading = true
    @Published var loadError: String?
    
    func loadCards(surface: Surface, customizer: ContentCardCustomizing) {
        showLoading = true
        loadError = nil
        listener.onDismissCard = { [weak self] card in
            DispatchQueue.main.async {
                self?.cards.removeAll { $0.id == card.id }
            }
        }
        Messaging.getContentCardsUI(for: surface, customizer: customizer, listener: listener) { [weak self] result in
            DispatchQueue.main.async {
                self?.showLoading = false
                switch result {
                case .success(let list):
                    self?.cards = list.sorted { $0.priority > $1.priority }
                case .failure(let error):
                    self?.loadError = error.localizedDescription
                    print("Content cards UI failed: \(error)")
                }
            }
        }
    }
}

/// 表示・閉じる・タップのイベントを受け取る（テンプレートビュー用）
final class ContentCardListenerForBridge: ContentCardUIEventListening {
    var onDismissCard: ((ContentCardUI) -> Void)?
    
    func onDisplay(_ card: ContentCardUI) {
        print("ContentCard Displayed: \(card.id)")
    }
    
    func onDismiss(_ card: ContentCardUI) {
        print("ContentCard Dismissed: \(card.id)")
        onDismissCard?(card)
    }
    
    func onInteract(_ card: ContentCardUI, _ interactionId: String, actionURL: URL?) -> Bool {
        print("ContentCard Interacted: \(interactionId)")
        return false
    }
}

/// デモ CardCustomizer に準拠: LargeImageTemplate / SmallImageTemplate / ImageOnlyTemplate を同一スタイルでカスタマイズ
final class ContentCardCustomizerForBridge: ContentCardCustomizing {
    func customize(template: LargeImageTemplate) {
        template.title.textColor = .primary
        template.title.font = .subheadline
        template.body?.textColor = .secondary
        template.body?.font = .caption
        template.buttons?.first?.text.font = .system(size: 13)
        template.buttons?.first?.text.textColor = .primary
        template.buttons?.first?.modifier = AEPViewModifier(ButtonModifierForBridge())
        template.rootVStack.spacing = 10
        template.textVStack.alignment = .leading
        template.textVStack.spacing = 10
        template.buttonHStack.modifier = AEPViewModifier(ButtonHStackModifierForBridge())
        template.rootVStack.modifier = AEPViewModifier(RootVStackModifierForBridge())
        template.dismissButton?.image.iconColor = .primary
        template.dismissButton?.image.iconFont = .system(size: 10)
    }
    
    func customize(template: SmallImageTemplate) {
        template.title.textColor = .primary
        template.title.font = .subheadline
        template.body?.textColor = .secondary
        template.body?.font = .caption
        template.buttons?.first?.text.font = .system(size: 13)
        template.buttons?.first?.text.textColor = .primary
        template.buttons?.first?.modifier = AEPViewModifier(ButtonModifierForBridge())
        template.rootHStack.spacing = 10
        template.textVStack.alignment = .leading
        template.textVStack.spacing = 10
        template.buttonHStack.modifier = AEPViewModifier(ButtonHStackModifierForBridge())
        template.rootHStack.modifier = AEPViewModifier(RootHStackModifierForBridge())
        template.dismissButton?.image.iconColor = .primary
        template.dismissButton?.image.iconFont = .system(size: 10)
    }
    
    func customize(template: ImageOnlyTemplate) {
        template.dismissButton?.image.iconColor = .primary
        template.dismissButton?.image.iconFont = .system(size: 10)
    }
}

private struct RootVStackModifierForBridge: ViewModifier {
    func body(content: Content) -> some View {
        content
            .frame(maxHeight: .infinity, alignment: .leading)
            .padding()
    }
}

private struct RootHStackModifierForBridge: ViewModifier {
    func body(content: Content) -> some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
    }
}

private struct ButtonHStackModifierForBridge: ViewModifier {
    func body(content: Content) -> some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct ButtonModifierForBridge: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding()
            .background(Color.primary.opacity(0.1))
            .cornerRadius(10)
    }
}
