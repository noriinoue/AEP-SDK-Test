import Foundation
import SwiftUI
import WebKit
import AEPCore
import AEPServices
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

// MARK: - In-App Message: adbinapp://...?link=... のインターセプト → アプリ内 WebView
// URL 解釈と WebView 表示は AEPSdkBridge.handleInAppNavigation に一本化し、デリゲートは結果に応じて cancel/dismiss/track のみ行う。
private final class InAppWebViewNavigationDelegate: NSObject, WKNavigationDelegate {
    private weak var message: Message?
    fileprivate static var retainedDelegate: InAppWebViewNavigationDelegate?
    
    init(message: Message) {
        self.message = message
        super.init()
    }
    
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        let (opened, interaction) = AEPSdkBridge.handleInAppNavigation(url: url)
        if opened {
            decisionHandler(.cancel)
            DispatchQueue.main.async { [weak self] in
                self?.message?.dismiss(suppressAutoTrack: false)
                self?.message?.track(interaction, withEdgeEventType: .interact)
            }
        } else {
            decisionHandler(.allow)
        }
    }
}

// MARK: - In-App Message デリゲート（公式デモ MessagingDemoApp の MessageHandler に準拠）
// 参照: https://github.com/adobe/aepsdk-messaging-ios/tree/main/TestApps/MessagingDemoApp
// - Showable は FullscreenMessage として渡るため fullscreenMessage?.parent で Message を取得
// - handleJavascriptMessage は shouldShowMessage 内で登録（表示前に登録する公式のやり方）
// - WKWebView へのアクセスは DispatchQueue.main.async で行う（公式デモと同じ）
@objc private class InAppMessageDelegate: NSObject, MessagingDelegate {
    static let shared = InAppMessageDelegate()
    
    /// 公式デモと同様: shouldShowMessage 内で Message を取得し、handleJavascriptMessage 登録と WKWebView の navigationDelegate 設定を行う
    func shouldShowMessage(message: Showable) -> Bool {
        let fullscreenMessage = message as? FullscreenMessage
        let msg = fullscreenMessage?.parent ?? (message as? Message)
        guard let message = msg else { return true }
        
        // 公式デモと同様に JS ハンドラを登録（表示前に登録）
        message.handleJavascriptMessage("AEPInAppCallback") { [weak message] body in
            let payload: String
            if let s = body as? String {
                payload = s
            } else if let data = body, let jsonData = try? JSONSerialization.data(withJSONObject: data), let s = String(data: jsonData, encoding: .utf8) {
                payload = s
            } else {
                payload = ""
            }
            if !payload.isEmpty {
                sendToUnity(objectName: AEPSdkBridge.inAppMessageCallbackTarget, method: "OnInAppMessageAction", message: payload)
            }
            message?.track(payload.isEmpty ? "click" : payload, withEdgeEventType: .interact)
        }
        
        // 公式デモと同様: WKWebView へのアクセスは main スレッドで。ここで adbinapp インターセプト用の navigationDelegate を設定
        DispatchQueue.main.async {
            if let webView = message.view as? WKWebView {
                let navDelegate = InAppWebViewNavigationDelegate(message: message)
                InAppWebViewNavigationDelegate.retainedDelegate = navDelegate
                webView.navigationDelegate = navDelegate
            }
        }
        
        return true
    }
    
    func onShow(message: Showable) {
        // 必要なら表示時の追加処理（公式デモではログ程度）
    }
    
    func onDismiss(message: Showable) {
        InAppWebViewNavigationDelegate.retainedDelegate = nil
    }
}

// MARK: - In-App WebView（link をアプリ内で表示する用）
private final class InAppWebViewController: UIViewController {
    private let url: URL
    private let onClose: () -> Void
    
    init(url: URL, onClose: @escaping () -> Void) {
        self.url = url
        self.onClose = onClose
        super.init(nibName: nil, bundle: nil)
    }
    
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .white
        
        let webView = WKWebView(frame: .zero, configuration: WKWebViewConfiguration())
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.allowsBackForwardNavigationGestures = true
        view.addSubview(webView)
        
        let closeButton = UIButton(type: .system)
        closeButton.setTitle("閉じる", for: .normal)
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        view.addSubview(closeButton)
        
        NSLayoutConstraint.activate([
            closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            closeButton.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            webView.topAnchor.constraint(equalTo: closeButton.bottomAnchor, constant: 8),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        
        webView.load(URLRequest(url: url))
    }
    
    @objc private func closeTapped() {
        dismiss(animated: true) { [weak self] in
            self?.onClose()
        }
    }
}

// MARK: - アプリ内 WebView 表示（MessagingDelegate で adbinapp://dismiss?link=... をインターセプトしたときに使用）
@objc public class AEPSdkBridge: NSObject {
    private static var isInitialized = false
    private static var initializationCallbacks: [(Bool) -> Void] = []
    
    /// In-App Message のボタン押下時に Unity に送る先（C# から setInAppMessageCallbackTarget で設定。未設定時 "AEPManager"）
    static var inAppMessageCallbackTarget: String = "AEPManager"
    
    @objc(setInAppMessageCallbackTarget:)
    public static func setInAppMessageCallbackTarget(_ name: String) {
        inAppMessageCallbackTarget = normalizeTarget(name)
    }
    
    /// surface 未指定時は "square"（Android の normalizeSurface と同一契約）
    private static func normalizeSurface(_ s: String?) -> String {
        let t = (s ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? "square" : t
    }
    
    /// コールバック先未指定時は "AEPManager"（Android の normalizeTarget と同一契約）
    private static func normalizeTarget(_ s: String?) -> String {
        let t = (s ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? "AEPManager" : t
    }
    
    // 非同期でSDKを初期化（appId は Unity C# が StreamingAssets から読み渡す。プリフェッチは C# が初期化完了後に呼ぶ）
    @objc(setupSDKWithAppId:callback:)
    public static func setupSDK(appId: String, callback: @escaping (Bool) -> Void) {
        let appIdTrimmed = (appId ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if appIdTrimmed.isEmpty {
            print("AEP SDK initialization failed: appId is empty. Set Assets/StreamingAssets/AEPAppId.txt (see AEPAppId.txt.sample).")
            DispatchQueue.main.async { callback(false) }
            return
        }
        
        if isInitialized {
            DispatchQueue.main.async { callback(true) }
            return
        }
        
        initializationCallbacks.append(callback)
        if initializationCallbacks.count > 1 {
            return
        }
        
        print("AEP SDK initialization started (async)")
        
        MobileCore.setLogLevel(.debug)
        
        // MobileCore.initialize のコールバックで初期化完了を検知（wait 処理を使わない）
        // https://developer.adobe.com/client-sdks/home/base/mobile-core/api-reference/#initialize
        MobileCore.initialize(appId: appIdTrimmed) {
            DispatchQueue.main.async {
                isInitialized = true
                print("AEP SDK initialization completed")
                MobileCore.messagingDelegate = InAppMessageDelegate.shared
                for cb in initializationCallbacks {
                    cb(true)
                }
                initializationCallbacks.removeAll()
            }
        }
    }
    
    /// コンテンツカードをプリフェッチ。C# が初期化完了後に surfacePath / gameObjectName を指定して呼ぶ。
    @objc(prefetchContentCardsWithSurfacePath:gameObjectName:)
    public static func prefetchContentCards(surfacePath: String, gameObjectName: String) {
        let path = normalizeSurface(surfacePath)
        let target = normalizeTarget(gameObjectName)
        let surface = Surface(path: path)
        Messaging.updatePropositionsForSurfaces([surface]) { success in
            DispatchQueue.main.async {
                sendToUnity(objectName: target, method: "OnContentCardsPrefetched", message: success ? "success" : "failed")
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
        Assurance.startSession()
    }

    /// Edge にイベント送信。C# が組み立てた JSON を XDM にマージして送る（Android と同一契約）。
    @objc public static func sendEvent(_ eventName: String, jsonData: String) {
        var xdmData: [String: Any] = ["eventType": eventName]
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
    
    /// 手動で Proposition 更新。完了は gameObjectName の OnPropositionsUpdated へ（Android と同一契約）。
    @objc(updatePropositionsManuallyWithSurfacePath:gameObjectName:)
    public static func updatePropositionsManually(_ surfacePath: String, gameObjectName: String) {
        let path = normalizeSurface(surfacePath)
        let target = normalizeTarget(gameObjectName)
        let surface = Surface(path: path)
        Messaging.updatePropositionsForSurfaces([surface]) { success in
            DispatchQueue.main.async {
                sendToUnity(objectName: target, method: "OnPropositionsUpdated", message: success ? "success:\(path)" : "failed:\(path)")
            }
        }
    }
    
    // MARK: - adbinapp → アプリ内 WebView（URL 解釈と表示をここに集約）
    private static var webViewHostingController: UIViewController?
    
    /// ナビゲーション URL を解釈し、処理した場合は true と interaction を返す。デリゲートは cancel + dismiss + track を行う。
    /// - adbinapp://dismiss?interaction=cancel → モーダルを閉じるだけ
    /// - adbinapp://dismiss?interaction=clicked&link=... → 外部ブラウザで開く
    /// - adbinapp://dismiss?interaction=webview&link=... → アプリ内 WebView で開く
    /// - http(s)://... → アプリ内 WebView で開く
    static func handleInAppNavigation(url: URL) -> (opened: Bool, interaction: String) {
        let scheme = url.scheme?.lowercased() ?? ""
        
        if scheme == "adbinapp" {
            guard let comp = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
                return (false, "webview")
            }
            let interaction = comp.queryItems?.first(where: { $0.name == "interaction" })?.value ?? "webview"
            
            // interaction=cancel → モーダルを閉じるだけ
            if interaction == "cancel" {
                return (true, "cancel")
            }
            
            let linkValue = comp.queryItems?.first(where: { $0.name == "link" })?.value?.removingPercentEncoding
                ?? comp.queryItems?.first(where: { $0.name == "target" })?.value?.removingPercentEncoding
            guard let link = linkValue?.trimmingCharacters(in: .whitespacesAndNewlines), !link.isEmpty,
                  let linkURL = URL(string: link) else {
                return (true, interaction)
            }
            
            let loadURL: URL?
            if linkURL.scheme?.lowercased() == "adbinapp" {
                var c = URLComponents(url: linkURL, resolvingAgainstBaseURL: false)
                c?.scheme = "https"
                loadURL = c?.url
            } else if linkURL.scheme?.lowercased() == "https" || linkURL.scheme?.lowercased() == "http" {
                loadURL = linkURL
            } else {
                loadURL = nil
            }
            
            // interaction=clicked → 外部ブラウザで開く
            if interaction == "clicked" {
                if let urlToOpen = loadURL {
                    DispatchQueue.main.async {
                        UIApplication.shared.open(urlToOpen)
                    }
                }
                return (true, "clicked")
            }
            
            // interaction=webview → アプリ内 WebView で開く
            if interaction == "webview", let urlToLoad = loadURL {
                DispatchQueue.main.async {
                    guard let unityVC = unityGetViewController() else { return }
                    let webVC = InAppWebViewController(url: urlToLoad) {
                        webViewHostingController = nil
                    }
                    webViewHostingController = webVC
                    unityVC.present(webVC, animated: true)
                }
                return (true, "webview")
            }
            
            return (true, interaction)
        }
        
        // 直接 http(s) URL → アプリ内 WebView で開く
        if scheme == "https" || scheme == "http" {
            DispatchQueue.main.async {
                guard let unityVC = unityGetViewController() else { return }
                let webVC = InAppWebViewController(url: url) {
                    webViewHostingController = nil
                }
                webViewHostingController = webVC
                unityVC.present(webVC, animated: true)
            }
            return (true, "webview")
        }
        
        return (false, "webview")
    }
    
    /// 文字列 URL で WebView を開く（ObjC 互換）。中身は handleInAppNavigation に委譲。
    @objc(openWebViewWithURLIfNeeded:)
    public static func openWebViewWithURLIfNeeded(_ link: String) -> Bool {
        let trimmed = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let url = URL(string: trimmed) else { return false }
        return handleInAppNavigation(url: url).opened
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
        let path = normalizeSurface(surfacePath)
        let style = (templateStyle.isEmpty ? "large" : templateStyle)
        DispatchQueue.main.async {
            guard let unityViewController = unityGetViewController() else { return }
            let surface = Surface(path: path)
            let view = ContentCardsSwiftUIView(
                surfacePath: path,
                surface: surface,
                templateStyle: style,
                onClose: {
                    dismissContentCardsTemplateView()
                }
            )
            
            let hostingController = UIHostingController(rootView: view)
            hostingController.view.backgroundColor = .systemBackground
            hostingController.modalPresentationStyle = .pageSheet
            
            contentCardsTemplateHostingController = hostingController
            unityViewController.present(hostingController, animated: true)
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
            }
        }
    }
    
    /// コンテンツカードを取得し、AJO 生データを JSON で Unity に送る。フラット化は C# 側で共通処理（Android と同一契約）。
    @objc(getContentCardsForUnity:callback:)
    public static func getContentCardsForUnity(_ surfacePath: String, callback: @escaping (String) -> Void) {
        let surface = Surface(path: normalizeSurface(surfacePath))
        Messaging.getPropositionsForSurfaces([surface]) { propositionsDict, error in
            DispatchQueue.main.async {
                var jsonResult: String
                
                if let error = error {
                    jsonResult = "{\"error\":\"\(error.localizedDescription)\"}"
                } else if let propositions = propositionsDict?[surface], !propositions.isEmpty {
                    var rawCards: [[String: Any]] = []
                    for proposition in propositions {
                        for item in proposition.items where item.schema == .contentCard {
                            guard let contentCardSchemaData = item.contentCardSchemaData,
                                  let contentDict = contentCardSchemaData.content as? [String: Any] else { continue }
                            rawCards.append(["content": contentDict])
                            contentCardSchemaData.track(withEdgeEventType: .display)
                        }
                    }
                    if let jsonData = try? JSONSerialization.data(withJSONObject: ["cards": rawCards, "error": NSNull()]),
                       let jsonString = String(data: jsonData, encoding: .utf8) {
                        jsonResult = jsonString
                    } else {
                        jsonResult = "{\"error\":\"Failed to serialize content cards\"}"
                    }
                } else {
                    jsonResult = "{\"cards\":[],\"error\":null}"
                }
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
