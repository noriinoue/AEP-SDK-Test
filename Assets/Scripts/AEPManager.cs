using UnityEngine;
using UnityEngine.UI;
using TMPro;
using System.Runtime.InteropServices;
using System.Collections.Generic;
using System;
using System.Collections;
using UnityEngine.Networking;

// ============================================================
// Data Classes
// ============================================================

/// <summary>
/// コンテンツカードの個別データ構造
/// </summary>
[Serializable]
public class ContentCardData
{
    public string templateType;
    public string title;
    public string body;
    public string imageUrl;
    public string actionUrl;
    /// <summary>ボタンラベル（AJOの buttons[0].text.content）</summary>
    public string buttonText;
}

/// <summary>
/// ネイティブコードから受け取るコンテンツカードのレスポンス構造
/// </summary>
[Serializable]
public class ContentCardsResponse
{
    public List<ContentCardData> cards;
    public string error;
}

// ============================================================
// Main Class: AEP SDK Manager
// ============================================================

/// <summary>
/// Adobe Experience Platform (AEP) SDKとUnityの橋渡しを行うマネージャークラス
/// - SDK初期化
/// - イベントトラッキング
/// - Identity管理
/// - Content Cards表示
/// </summary>
public class AEPManager : MonoBehaviour
{
    // ============================================================
    // Serialized Fields
    // ============================================================
    
    [Header("UI Components")]
    [SerializeField] private ParticleSystem clickEffect;
    [SerializeField] private Transform buttonTransform;
    [SerializeField] private TMP_InputField identityInputField;
    [SerializeField] private GameObject cardsSurface;
    
    [Header("Content Cards - Surface")]
    [Tooltip("Surface のパスを入力。空欄または未設定時は \"square\" を使用。")]
    [SerializeField] private TMP_InputField surfaceInputField;
    
    [Header("Content Cards - Unity Area (Blank Area)")]
    [Tooltip("Scroll View の Content。必ず Canvas 配下の Scroll View > Viewport > Content を指定してください。ここに入っていないとカードは描画されません。")]
    [SerializeField] private RectTransform contentCardsContainer;
    [Tooltip("1枚のカード用プレハブ。子: RawImage(画像), TMP_Text×2(タイトル・本文), Button(CTA), 任意で CloseButton という名前の閉じるボタン。枠はルートに Image＋枠用スプライト または Outline で。")]
    [SerializeField] private GameObject contentCardItemPrefab;

    [Header("Content Cards Buttons")]
    [Tooltip("Text: JSON をログエリアに表示")]
    [SerializeField] private Button showContentCardsTextButton;
    [Tooltip("Native: SDK テンプレート通りにネイティブドロワーで表示")]
    [SerializeField] private Button showContentCardsNativeButton;
    [Tooltip("Scroll View: 同一画面の Scroll View にプレハブで表示")]
    [SerializeField] private Button showContentCardsScrollViewButton;
    [SerializeField] private Button updatePropositionsButton;
    
    [Header("Debug Settings")]
    [Tooltip("起動時にAssuranceを自動起動するかどうか（デバッグ用）")]
    [SerializeField] private bool autoStartAssuranceOnLaunch = false;
    
    // ============================================================
    // Private Static Fields (SDK State Management)
    // ============================================================
    
    private static bool isInitialized = false;
    private static bool isInitializing = false;
    private static Queue<System.Action> pendingActions = new Queue<System.Action>();
    private static AEPManager instance;
    
    // ============================================================
    // Native iOS Bridge (DllImport) — iOS ビルド時のみリンク（Android では未定義にしてリンカエラーを防ぐ）
    // ============================================================
    
    /// <summary>StreamingAssets 内の AEP Launch App ID ファイル名（実体は .gitignore で秘匿）</summary>
    private const string AEP_APP_ID_FILENAME = "AEPAppId.txt";
    
#if UNITY_IOS
    [DllImport("__Internal")]
    private static extern void _ios_aep_initialize(string appId, string gameObjectName, string callbackMethodName);
    
    [DllImport("__Internal")]
    private static extern void _ios_aep_startAssurance();

    [DllImport("__Internal")]
    private static extern void _ios_aep_sendEvent(string eventName, string jsonData);
    
    [DllImport("__Internal")]
    private static extern void _ios_aep_updateIdentities(string identifierType, string identifier);
    
    [DllImport("__Internal")]
    private static extern void _ios_aep_getContentCardsForUnity(string surfacePath, string gameObjectName, string callbackMethodName);
    
    [DllImport("__Internal")]
    private static extern void _ios_aep_showContentCardsWithTemplates(string surfacePath, string templateStyle);
    
    [DllImport("__Internal")]
    private static extern void _ios_aep_updatePropositionsManually(string surfacePath);
#endif

    // ============================================================
    // Unity Lifecycle Methods
    // ============================================================
    
    void Awake()
    {
        // シングルトンパターンで初期化を1回だけ実行
        if (instance == null)
        {
            instance = this;
            DontDestroyOnLoad(gameObject);
            StartCoroutine(InitializeSDKAsync());
        }
        else if (instance != this)
        {
            Destroy(gameObject);
        }
    }

    void Start()
    {
        // 初期化時にエフェクトの位置をボタンに合わせる
        if (clickEffect != null && buttonTransform != null)
        {
            clickEffect.transform.position = new Vector3(buttonTransform.position.x, buttonTransform.position.y, -5);
        }
    }
    
    // ============================================================
    // SDK Initialization Methods
    // ============================================================
    
    /// <summary>
    /// AEP SDKを非同期で初期化（iOS: StreamingAssets/AEPAppId.txt から appId を読み取りネイティブに渡す）
    /// </summary>
    private IEnumerator InitializeSDKAsync()
    {
        if (isInitialized || isInitializing)
        {
            yield break;
        }
        
        isInitializing = true;
        Debug.Log("AEP SDK initialization started (async)...");
        
        #if UNITY_IOS && !UNITY_EDITOR
            yield return LoadAEPAppIdAndInitialize();
        #elif UNITY_ANDROID && !UNITY_EDITOR
            yield return LoadAEPAppIdAndInitialize();
        #else
            yield return new WaitForSeconds(0.1f);
            OnSDKInitialized("success");
        #endif
        
        yield return null;
    }
    
    /// <summary>
    /// StreamingAssets/AEPAppId.txt を読み、ネイティブ初期化を呼び出す。
    /// ファイルがない場合はエラーにしてコールバックで失敗を返す。
    /// iOS では streamingAssetsPath がスキームなしパスを返すため、UnityWebRequest 用に file:// を付与する。
    /// </summary>
    private IEnumerator LoadAEPAppIdAndInitialize()
    {
        string path = System.IO.Path.Combine(Application.streamingAssetsPath, AEP_APP_ID_FILENAME);
        string url = path;
        if (!path.Contains("://"))
        {
            url = "file://" + path;
        }
        using (UnityWebRequest req = UnityWebRequest.Get(url))
        {
            yield return req.SendWebRequest();
            string appId = null;
            if (req.result == UnityWebRequest.Result.Success && !string.IsNullOrWhiteSpace(req.downloadHandler?.text))
            {
                appId = req.downloadHandler.text.Trim();
            }
            if (string.IsNullOrEmpty(appId))
            {
                Debug.LogError(
                    "AEP App ID not found. Copy Assets/StreamingAssets/AEPAppId.txt.sample to AEPAppId.txt and set your Launch app id. " +
                    "Do not commit AEPAppId.txt.");
                OnSDKInitialized("failed");
                yield break;
            }
#if UNITY_IOS
            _ios_aep_initialize(appId, gameObject.name, "OnSDKInitialized");
#elif UNITY_ANDROID && !UNITY_EDITOR
            using (var jc = new UnityEngine.AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
            {
                jc.CallStatic("initialize", appId, gameObject.name, "OnSDKInitialized");
            }
#endif
        }
    }
    
    /// <summary>
    /// ネイティブコードからの初期化完了コールバック
    /// </summary>
    private void OnSDKInitialized(string result)
    {
        isInitializing = false;
        
        if (result == "success")
        {
            isInitialized = true;
            Debug.Log("AEP SDK initialization completed successfully!");
            
            // キューに溜まっていた操作を実行
            while (pendingActions.Count > 0)
            {
                var action = pendingActions.Dequeue();
                try
                {
                    action?.Invoke();
                }
                catch (Exception e)
                {
                    Debug.LogError($"Error executing pending action: {e.Message}");
                }
            }
            
            // 設定されている場合、Assuranceを非同期で自動起動
            if (autoStartAssuranceOnLaunch)
            {
                StartCoroutine(StartAssuranceSessionAsync());
            }
        }
        else
        {
            Debug.LogError("AEP SDK initialization failed!");
        }
    }
    
    /// <summary>
    /// SDK初期化完了を待つヘルパーメソッド
    /// </summary>
    private void ExecuteWhenInitialized(System.Action action)
    {
        if (isInitialized)
        {
            // 既に初期化済みなら即座に実行
            action?.Invoke();
        }
        else
        {
            // 初期化待ちならキューに追加
            Debug.Log("SDK not initialized yet. Queueing action...");
            pendingActions.Enqueue(action);
        }
    }
    
    // ============================================================
    // Debug & Assurance Methods
    // ============================================================
    
    /// <summary>
    /// Assuranceセッションを非同期で起動（起動をブロックしない）
    /// </summary>
    private IEnumerator StartAssuranceSessionAsync()
    {
        // 少し待ってからAssuranceを起動（SDK初期化直後は避ける）
        yield return new WaitForSeconds(0.5f);
        
        Debug.Log("Starting Assurance session asynchronously...");
        
        #if UNITY_IOS && !UNITY_EDITOR
            _ios_aep_startAssurance();
        #elif UNITY_ANDROID && !UNITY_EDITOR
            using (var jc = new UnityEngine.AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
                jc.CallStatic("startAssuranceSession");
        #else
            Debug.Log("Assurance session would start on device (async)");
        #endif
    }
    
    /// <summary>
    /// Assuranceを手動で起動（ボタンから呼び出し可能）
    /// 注: autoStartAssuranceOnLaunchがtrueの場合は起動時に自動起動されます
    /// </summary>
    public void StartAssuranceSession()
    {
        #if UNITY_IOS && !UNITY_EDITOR
            ExecuteWhenInitialized(() => {
                Debug.Log("Manually starting Assurance session...");
                _ios_aep_startAssurance();
            });
        #elif UNITY_ANDROID && !UNITY_EDITOR
            ExecuteWhenInitialized(() => {
                Debug.Log("Manually starting Assurance session...");
                using (var jc = new UnityEngine.AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
                    jc.CallStatic("startAssuranceSession");
            });
        #else
            Debug.Log("Assurance only available on iOS/Android device");
        #endif
    }

    // ============================================================
    // Tracking & Event Methods
    // ============================================================
    
    /// <summary>
    /// カスタムイベントを送信（パーティクルエフェクト付き）
    /// </summary>
    public void SendEvent()
    {
        // パーティクルエフェクトを再生
        if (clickEffect != null)
        {
            clickEffect.Play();
            Debug.Log("Particle effect played!");
        }

        ExecuteWhenInitialized(() => {
            Dictionary<string, object> data = new Dictionary<string, object>{
                {"clickedObject", "SendEvent"},
            };
            string jsonData = DictToJson(data);

            #if UNITY_IOS && !UNITY_EDITOR
                _ios_aep_sendEvent("application.click", jsonData);
                Debug.Log("Edge.sendEvent called with SendEvent");
            #elif UNITY_ANDROID && !UNITY_EDITOR
                using (var jc = new UnityEngine.AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
                    jc.CallStatic("sendEvent", "application.click", jsonData);
                Debug.Log("Edge.sendEvent called (Android) with SendEvent");
            #else
                Debug.Log($"SendEvent: would be called on device with data: {jsonData}");
            #endif
        });
    }

    /// <summary>
    /// Identity.updateIdentitiesを使用してCRM IDを更新（推奨方式）
    /// </summary>
    public void UpdateIdentities()
    {
        string crmId = GetCrmIdFromInput();
        
        ExecuteWhenInitialized(() => {
            Dictionary<string, object> data = new Dictionary<string, object>{
                {"clickedObject", "UpdateIdentities"},
                {"crmId", crmId}
            };
            string jsonData = DictToJson(data);
            
            #if UNITY_IOS && !UNITY_EDITOR
                _ios_aep_sendEvent("application.click", jsonData);
                _ios_aep_updateIdentities("extendedPersonalId", crmId);
                Debug.Log($"Identity.updateIdentities called with CRM ID: {crmId}");
            #elif UNITY_ANDROID && !UNITY_EDITOR
                using (var jc = new UnityEngine.AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
                {
                    jc.CallStatic("sendEvent", "application.click", jsonData);
                    jc.CallStatic("updateIdentities", "extendedPersonalId", crmId);
                }
                Debug.Log($"Identity.updateIdentities called (Android) with CRM ID: {crmId}");
            #else
                Debug.Log($"UpdateIdentities: would update CRM ID: {crmId} on device with data: {jsonData}");
            #endif
        });
    }
    
    /// <summary>
    /// InputFieldまたはデフォルト値からCRM IDを取得
    /// </summary>
    private string GetCrmIdFromInput()
    {
        string crmId = "C00001"; // デフォルト値
        
        if (identityInputField != null && !string.IsNullOrEmpty(identityInputField.text))
        {
            crmId = identityInputField.text;
        }
        
        return crmId;
    }

    /// <summary>
    /// Surface 入力欄の値。空または未設定時は "square"
    /// </summary>
    private string GetSurfacePath()
    {
        if (surfaceInputField != null && !string.IsNullOrEmpty(surfaceInputField.text))
        {
            string t = surfaceInputField.text.Trim();
            if (t.Length > 0) return t;
        }
        return "square";
    }

    // ============================================================
    // Content Cards Methods (Public API)
    // ============================================================
    
    /// <summary>
    /// Text: コンテンツカードの JSON をログエリアに表示（ボタン「Text」用）
    /// </summary>
    public void ShowContentCardsText()
    {
        string surfacePath = GetSurfacePath();
        
        Dictionary<string, object> data = new Dictionary<string, object>{
            {"clickedObject", "ShowContentCardsText"},
            {"surfacePath", surfacePath}
        };
        string jsonData = DictToJson(data);

        #if UNITY_IOS && !UNITY_EDITOR
            _ios_aep_sendEvent("application.click", jsonData);
            _ios_aep_getContentCardsForUnity(surfacePath, gameObject.name, "OnContentCardsReceivedForText");
            Debug.Log($"ShowContentCardsText: requesting JSON for text area");
        #elif UNITY_ANDROID && !UNITY_EDITOR
            using (var jc = new UnityEngine.AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
            {
                jc.CallStatic("sendEvent", "application.click", jsonData);
                jc.CallStatic("getContentCardsForUnity", surfacePath, gameObject.name, "OnContentCardsReceivedForText");
            }
            Debug.Log($"ShowContentCardsText: requesting JSON for text area (Android)");
        #else
            Debug.Log($"ShowContentCardsText: would request JSON on device");
        #endif
    }

    /// <summary>
    /// Native: SDK のテンプレート通りにネイティブドロワーで表示（ボタン「Native」用）。AJO の施策で Large/Small 等が決まる。
    /// </summary>
    public void ShowContentCardsNative()
    {
        string surfacePath = GetSurfacePath();
        ExecuteWhenInitialized(() => {
            Dictionary<string, object> data = new Dictionary<string, object>{
                {"clickedObject", "ShowContentCardsNative"},
                {"surfacePath", surfacePath}
            };
            string jsonData = DictToJson(data);
            #if UNITY_IOS && !UNITY_EDITOR
                _ios_aep_sendEvent("application.click", jsonData);
                _ios_aep_showContentCardsWithTemplates(surfacePath, "large");
                Debug.Log("ShowContentCardsNative: opening native drawer");
            #elif UNITY_ANDROID && !UNITY_EDITOR
                using (var jc = new UnityEngine.AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
                {
                    jc.CallStatic("sendEvent", "application.click", jsonData);
                    jc.CallStatic("showContentCardsWithTemplates", surfacePath, "large");
                }
                Debug.Log("ShowContentCardsNative: (Android uses Scroll View for cards)");
            #else
                Debug.Log("ShowContentCardsNative: would open native drawer on device");
            #endif
        });
    }
    
    /// <summary>
    /// Scroll View: コンテンツカードを同一画面の Scroll View にプレハブで表示（ボタン「Scroll View」用）
    /// </summary>
    public void ShowContentCardsScrollView()
    {
        string surfacePath = GetSurfacePath();
        ExecuteWhenInitialized(() => {
            Dictionary<string, object> data = new Dictionary<string, object>{
                {"clickedObject", "ShowContentCardsScrollView"},
                {"surfacePath", surfacePath}
            };
            string jsonData = DictToJson(data);
            #if UNITY_IOS && !UNITY_EDITOR
                _ios_aep_sendEvent("application.click", jsonData);
                _ios_aep_getContentCardsForUnity(surfacePath, gameObject.name, "OnContentCardsReceivedForScrollView");
                Debug.Log("ShowContentCardsScrollView: updating Scroll View");
            #elif UNITY_ANDROID && !UNITY_EDITOR
                using (var jc = new UnityEngine.AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
                {
                    jc.CallStatic("sendEvent", "application.click", jsonData);
                    jc.CallStatic("getContentCardsForUnity", surfacePath, gameObject.name, "OnContentCardsReceivedForScrollView");
                }
                Debug.Log("ShowContentCardsScrollView: updating Scroll View (Android)");
            #else
                Debug.Log("ShowContentCardsScrollView: would update Scroll View on device");
            #endif
        });
    }
    
    /// <summary>
    /// Propositionを手動で更新（任意のタイミングで実行可能）
    /// </summary>
    public void UpdatePropositionsManually()
    {
        string surfacePath = GetSurfacePath();
        
        ExecuteWhenInitialized(() => {
            // UIに更新中メッセージを表示
            if (cardsSurface != null)
            {
                TMP_Text cardText = cardsSurface.GetComponentInChildren<TMP_Text>();
                if (cardText != null)
                {
                    cardText.text = "Updating Content Cards...\n\nFetching latest data from server.";
                }
            }
            
            Dictionary<string, object> data = new Dictionary<string, object>{
                {"clickedObject", "UpdatePropositionsManually"},
                {"surfacePath", surfacePath}
            };
            string jsonData = DictToJson(data);

            #if UNITY_IOS && !UNITY_EDITOR
                _ios_aep_sendEvent("application.click", jsonData);
                _ios_aep_updatePropositionsManually(surfacePath);
                Debug.Log($"Manually updating propositions for: {surfacePath}");
            #elif UNITY_ANDROID && !UNITY_EDITOR
                using (var jc = new UnityEngine.AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
                {
                    jc.CallStatic("sendEvent", "application.click", jsonData);
                    jc.CallStatic("updatePropositionsManually", surfacePath);
                }
                Debug.Log($"Manually updating propositions for: {surfacePath} (Android)");
            #else
                Debug.Log($"UpdatePropositionsManually: would update on device");
                StartCoroutine(SimulatePropositionUpdate(surfacePath));
            #endif
        });
    }
    
    /// <summary>
    /// エディタ用のシミュレーション
    /// </summary>
    private IEnumerator SimulatePropositionUpdate(string surfacePath)
    {
        yield return new WaitForSeconds(2.0f);
        OnPropositionsUpdated($"success:{surfacePath}");
    }
    
    // ============================================================
    // Content Cards Callback Methods (Called from Native)
    // ============================================================
    
    /// <summary>
    /// Text ボタン用: ネイティブから受け取ったJSONを整形してテキストエリアに表示
    /// </summary>
    public void OnContentCardsReceivedForText(string jsonResponse)
    {
        Debug.Log($"Content Cards JSON (for text): {jsonResponse?.Length ?? 0} chars");
        if (cardsSurface != null)
        {
            TMP_Text cardText = cardsSurface.GetComponentInChildren<TMP_Text>();
            if (cardText != null)
                cardText.text = string.IsNullOrEmpty(jsonResponse) ? "(empty)" : PrettyPrintJson(jsonResponse);
        }
    }

    /// <summary>
    /// Scroll View ボタン用: ネイティブから受け取った JSON をパースして Scroll View を更新
    /// </summary>
    public void OnContentCardsReceivedForScrollView(string jsonResponse)
    {
        Debug.Log($"Content Cards JSON (for Scroll View): {jsonResponse?.Length ?? 0} chars");
        try
        {
            ContentCardsResponse response = JsonUtility.FromJson<ContentCardsResponse>(jsonResponse);
            if (!string.IsNullOrEmpty(response.error))
            {
                DisplayErrorMessage($"Error: {response.error}");
                return;
            }
            if (response.cards == null || response.cards.Count == 0)
            {
                DisplayErrorMessage("No content cards available");
                return;
            }
            if (contentCardsContainer == null)
            {
                DisplayErrorMessage("Content Cards Container not set in AEPManager.");
                return;
            }
            if (contentCardItemPrefab == null)
            {
                DisplayErrorMessage("Content Cards prefab not set in AEPManager.");
                return;
            }
            DisplayContentCardsInArea(response.cards);
        }
        catch (Exception e)
        {
            Debug.LogError($"Failed to parse content cards: {e.Message}");
            DisplayErrorMessage($"Parse Error: {e.Message}");
        }
    }
    
    /// <summary>
    /// コンテンツカードのprefetch完了コールバック（ネイティブから呼ばれる）
    /// </summary>
    public void OnContentCardsPrefetched(string result)
    {
        Debug.Log($"Content cards prefetched: {result}");
        SetContentCardButtonsInteractable(true);
    }
    
    private void SetContentCardButtonsInteractable(bool interactable)
    {
        if (showContentCardsTextButton != null) showContentCardsTextButton.interactable = interactable;
        if (showContentCardsNativeButton != null) showContentCardsNativeButton.interactable = interactable;
        if (showContentCardsScrollViewButton != null) showContentCardsScrollViewButton.interactable = interactable;
    }
    
    /// <summary>
    /// Propositionの手動更新完了コールバック（ネイティブから呼ばれる）
    /// </summary>
    public void OnPropositionsUpdated(string result)
    {
        Debug.Log($"Propositions manually updated: {result}");
        
        if (result.StartsWith("success"))
        {
            // 成功時の処理
            string surfacePath = result.Split(':')[1];
            Debug.Log($"Propositions updated successfully for surface: {surfacePath}");
            
            SetContentCardButtonsInteractable(true);

            // UIに成功メッセージを表示
            if (cardsSurface != null)
            {
                TMP_Text cardText = cardsSurface.GetComponentInChildren<TMP_Text>();
                if (cardText != null)
                {
                    cardText.text = "Content Card Update Complete\n\nThe latest content has been fetched from the server.\n\nYou can now display it using the 'Show Content Cards' buttons.";
                }
            }
            
            // 数秒後にメッセージをクリア（オプション）
            StartCoroutine(ClearMessageAfterDelay(3.0f));
        }
        else if (result.StartsWith("failed"))
        {
            // 失敗時の処理
            string surfacePath = result.Split(':')[1];
            Debug.LogError($"Failed to update propositions for surface: {surfacePath}");
            DisplayErrorMessage($"Update Failed\n\nSurface: {surfacePath}\n\nPlease check your network connection.");
        }
        else if (result.StartsWith("timeout"))
        {
            // タイムアウト時の処理
            string surfacePath = result.Split(':')[1];
            Debug.LogWarning($"Proposition update timed out for surface: {surfacePath}");
            DisplayErrorMessage($"Update Timed Out\n\nSurface: {surfacePath}\n\nThe request took too long. Please try again.");
        }
    }
    
    /// <summary>
    /// In-App Message 内のボタン押下時にネイティブから呼ばれる（MessagingDelegate + handleJavascriptMessage 経由）。
    /// メッセージ HTML で webkit.messageHandlers.AEPInAppCallback.postMessage(action) を呼ぶと、action がここに渡る。
    /// </summary>
    public void OnInAppMessageAction(string payload)
    {
        if (string.IsNullOrEmpty(payload)) return;
        Debug.Log($"[In-App Message] Button/action received: {payload}");
        // 必要に応じて payload に応じた画面遷移・分析・UI 更新などを実装する
    }
    
    /// <summary>
    /// 一定時間後にメッセージをクリア
    /// </summary>
    private IEnumerator ClearMessageAfterDelay(float delay)
    {
        yield return new WaitForSeconds(delay);
        
        if (cardsSurface != null)
        {
            TMP_Text cardText = cardsSurface.GetComponentInChildren<TMP_Text>();
            if (cardText != null)
            {
                cardText.text = "";
            }
        }
    }
    
    // ============================================================
    // UI Display Helper Methods (プレハブでレイアウト = その通り表示)
    // ============================================================
    
    /// <summary>
    /// プレハブでカードを並べて表示。プレハブのレイアウトがそのまま使われます。
    /// 注意: contentCardsContainer は Canvas 配下の Scroll View の Content である必要があります。
    /// </summary>
    private void DisplayContentCardsInArea(List<ContentCardData> cards)
    {
        for (int i = contentCardsContainer.childCount - 1; i >= 0; i--)
            Destroy(contentCardsContainer.GetChild(i).gameObject);
        
        foreach (var card in cards)
        {
            GameObject item = Instantiate(contentCardItemPrefab, contentCardsContainer, false);
            item.SetActive(true);
            
            var layout = item.GetComponent<LayoutElement>();
            if (layout == null) layout = item.AddComponent<LayoutElement>();
            layout.preferredHeight = 120f;
            layout.minHeight = 80f;
            layout.flexibleWidth = 1f;
            
            var rawImage = item.GetComponentInChildren<RawImage>(true);
            if (rawImage != null)
            {
                var imgLE = rawImage.GetComponent<LayoutElement>();
                if (imgLE == null) imgLE = rawImage.gameObject.AddComponent<LayoutElement>();
                imgLE.preferredWidth = 80f;
                imgLE.preferredHeight = 80f;
            }
            var buttons = item.GetComponentsInChildren<Button>(true);
            Button ctaButton = null;
            foreach (var b in buttons)
            {
                if (b.gameObject.name == "CloseButton" || b.gameObject.name.Contains("Close"))
                {
                    b.onClick.RemoveAllListeners();
                    var cardObj = item;
                    b.onClick.AddListener(() => { if (cardObj != null) Destroy(cardObj); });
                }
                else
                    ctaButton = b;
            }
            var titleText = GetFirstTMPTextExcludingButtons(item, buttons);
            var bodyText = GetSecondTMPTextExcludingButtons(item, buttons);
            if (titleText != null)
                titleText.text = string.IsNullOrEmpty(card.title) ? "" : card.title;
            if (bodyText != null)
                bodyText.text = string.IsNullOrEmpty(card.body) ? "" : card.body;
            
            if (ctaButton != null)
            {
                var btnLabel = ctaButton.GetComponentInChildren<TMP_Text>(true);
                if (btnLabel != null)
                    btnLabel.text = string.IsNullOrEmpty(card.buttonText) ? "See more" : card.buttonText;
                string actionUrl = card.actionUrl;
                ctaButton.onClick.RemoveAllListeners();
                if (!string.IsNullOrEmpty(actionUrl))
                    ctaButton.onClick.AddListener(() => Application.OpenURL(actionUrl));
            }
            
            if (rawImage != null && !string.IsNullOrEmpty(card.imageUrl))
                StartCoroutine(LoadCardImage(rawImage, card.imageUrl));
            else if (rawImage != null)
                rawImage.gameObject.SetActive(false);
        }
        
        Debug.Log($"Content cards displayed in area: {cards.Count} cards");
    }
    
    private static TMP_Text GetFirstTMPTextExcludingButtons(GameObject root, Button[] buttons)
    {
        var all = root.GetComponentsInChildren<TMP_Text>(true);
        foreach (var t in all)
        {
            if (IsUnderAnyButton(t, buttons)) continue;
            return t;
        }
        return null;
    }
    
    private static TMP_Text GetSecondTMPTextExcludingButtons(GameObject root, Button[] buttons)
    {
        var all = root.GetComponentsInChildren<TMP_Text>(true);
        int index = 0;
        foreach (var t in all)
        {
            if (IsUnderAnyButton(t, buttons)) continue;
            if (index == 1) return t;
            index++;
        }
        return null;
    }
    
    private static bool IsUnderAnyButton(TMP_Text t, Button[] buttons)
    {
        if (buttons == null) return false;
        foreach (var b in buttons)
        {
            if (b != null && t.transform.IsChildOf(b.transform)) return true;
        }
        return false;
    }
    
    private IEnumerator LoadCardImage(RawImage rawImage, string imageUrl)
    {
        if (rawImage == null || string.IsNullOrEmpty(imageUrl)) yield break;
        using (var req = UnityWebRequestTexture.GetTexture(imageUrl))
        {
            yield return req.SendWebRequest();
            if (req.result == UnityWebRequest.Result.Success && req.downloadHandler is DownloadHandlerTexture dh)
            {
                rawImage.texture = dh.texture;
                rawImage.gameObject.SetActive(true);
            }
            else
                rawImage.gameObject.SetActive(false);
        }
    }
    
    /// <summary>
    /// エラーメッセージをUIに表示
    /// </summary>
    private void DisplayErrorMessage(string message)
    {
        if (cardsSurface != null)
        {
            TMP_Text cardText = cardsSurface.GetComponentInChildren<TMP_Text>();
            if (cardText != null)
            {
                cardText.text = message;
            }
        }
    }
    
    // ============================================================
    // Utility Methods
    // ============================================================
    
    /// <summary>
    /// JSON文字列をインデント付きで整形（人が読みやすい形式）
    /// </summary>
    private static string PrettyPrintJson(string json)
    {
        if (string.IsNullOrEmpty(json)) return json;
        var sb = new System.Text.StringBuilder();
        int indent = 0;
        bool inString = false;
        bool escape = false;
        for (int i = 0; i < json.Length; i++)
        {
            char c = json[i];
            if (escape) { sb.Append(c); escape = false; continue; }
            if (c == '\\' && inString) { sb.Append(c); escape = true; continue; }
            if ((c == '"') && !escape) { inString = !inString; sb.Append(c); continue; }
            if (inString) { sb.Append(c); continue; }
            if (c == '{' || c == '[') { sb.Append(c); sb.AppendLine(); indent++; sb.Append(new string(' ', indent * 2)); continue; }
            if (c == '}' || c == ']') { sb.AppendLine(); indent--; sb.Append(new string(' ', indent * 2)); sb.Append(c); continue; }
            if (c == ',') { sb.Append(c); sb.AppendLine(); sb.Append(new string(' ', indent * 2)); continue; }
            if (c == ':') { sb.Append(c); sb.Append(' '); continue; }
            sb.Append(c);
        }
        return sb.ToString();
    }
    
    /// <summary>
    /// DictionaryをJSON文字列に変換するヘルパーメソッド
    /// </summary>
    private string DictToJson(Dictionary<string, object> dict)
    {
        List<string> pairs = new List<string>();
        foreach (var kvp in dict)
        {
            string value = kvp.Value is string ? $"\"{kvp.Value}\"" : kvp.Value.ToString();
            pairs.Add($"\"{kvp.Key}\":{value}");
        }
        return "{" + string.Join(",", pairs.ToArray()) + "}";
    }
}