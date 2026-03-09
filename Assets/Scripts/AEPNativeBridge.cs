using System.Runtime.InteropServices;
using UnityEngine;

/// <summary>
/// OS別のネイティブAEP呼び出しを集約するブリッジ。
/// ここだけが UNITY_IOS / UNITY_ANDROID の条件分岐を持ち、AEPManager は共通ロジックのみ記述する。
/// </summary>
internal static class AEPNativeBridge
{
#if UNITY_IOS && !UNITY_EDITOR
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
    private static extern void _ios_aep_updatePropositionsManually(string surfacePath, string gameObjectName);

    [DllImport("__Internal")]
    private static extern void _ios_aep_prefetchContentCards(string surfacePath, string gameObjectName);

    [DllImport("__Internal")]
    private static extern void _ios_aep_setInAppMessageCallbackTarget(string gameObjectName);
#endif

    /// <summary>実機でネイティブSDKが利用可能か（エディタでは false）</summary>
    public static bool IsNativeAvailable
    {
        get
        {
#if UNITY_IOS && !UNITY_EDITOR
            return true;
#elif UNITY_ANDROID && !UNITY_EDITOR
            return true;
#else
            return false;
#endif
        }
    }

    public static void Initialize(string appId, string gameObjectName, string callbackMethodName)
    {
#if UNITY_IOS && !UNITY_EDITOR
        _ios_aep_initialize(appId, gameObjectName, callbackMethodName);
#elif UNITY_ANDROID && !UNITY_EDITOR
        using (var jc = new AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
            jc.CallStatic("initialize", appId, gameObjectName, callbackMethodName);
#else
        Debug.Log("AEPNativeBridge: Initialize skipped (editor or unsupported platform)");
#endif
    }

    public static void StartAssurance()
    {
#if UNITY_IOS && !UNITY_EDITOR
        _ios_aep_startAssurance();
#elif UNITY_ANDROID && !UNITY_EDITOR
        using (var jc = new AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
            jc.CallStatic("startAssuranceSession");
#else
        Debug.Log("AEPNativeBridge: Assurance only available on device");
#endif
    }

    public static void SendEvent(string eventName, string jsonData)
    {
#if UNITY_IOS && !UNITY_EDITOR
        _ios_aep_sendEvent(eventName, jsonData);
#elif UNITY_ANDROID && !UNITY_EDITOR
        using (var jc = new AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
            jc.CallStatic("sendEvent", eventName, jsonData ?? "");
#else
        Debug.Log($"AEPNativeBridge: SendEvent would run on device — {eventName}");
#endif
    }

    public static void UpdateIdentities(string identifierType, string identifier)
    {
#if UNITY_IOS && !UNITY_EDITOR
        _ios_aep_updateIdentities(identifierType ?? "", identifier ?? "");
#elif UNITY_ANDROID && !UNITY_EDITOR
        using (var jc = new AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
            jc.CallStatic("updateIdentities", identifierType ?? "", identifier ?? "");
#else
        Debug.Log("AEPNativeBridge: UpdateIdentities would run on device");
#endif
    }

    public static void GetContentCardsForUnity(string surfacePath, string gameObjectName, string callbackMethodName)
    {
#if UNITY_IOS && !UNITY_EDITOR
        _ios_aep_getContentCardsForUnity(surfacePath ?? "square", gameObjectName, callbackMethodName);
#elif UNITY_ANDROID && !UNITY_EDITOR
        using (var jc = new AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
            jc.CallStatic("getContentCardsForUnity", surfacePath ?? "square", gameObjectName, callbackMethodName);
#else
        Debug.Log("AEPNativeBridge: GetContentCardsForUnity would run on device");
#endif
    }

    public static void ShowContentCardsWithTemplates(string surfacePath, string templateStyle)
    {
#if UNITY_IOS && !UNITY_EDITOR
        _ios_aep_showContentCardsWithTemplates(surfacePath ?? "square", templateStyle ?? "large");
#elif UNITY_ANDROID && !UNITY_EDITOR
        using (var jc = new AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
            jc.CallStatic("showContentCardsWithTemplates", surfacePath ?? "square", templateStyle ?? "large");
#else
        Debug.Log("AEPNativeBridge: ShowContentCardsWithTemplates would run on device");
#endif
    }

    public static void UpdatePropositionsManually(string surfacePath, string gameObjectName)
    {
#if UNITY_IOS && !UNITY_EDITOR
        _ios_aep_updatePropositionsManually(surfacePath ?? "square", gameObjectName ?? "");
#elif UNITY_ANDROID && !UNITY_EDITOR
        using (var jc = new AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
            jc.CallStatic("updatePropositionsManually", surfacePath ?? "square", gameObjectName ?? "");
#else
        Debug.Log("AEPNativeBridge: UpdatePropositionsManually would run on device");
#endif
    }

    /// <summary>In-App Message の JS コールバック先を指定（iOS/Android 共通。初期化後に C# から呼ぶ）</summary>
    public static void SetInAppMessageCallbackTarget(string gameObjectName)
    {
#if UNITY_IOS && !UNITY_EDITOR
        _ios_aep_setInAppMessageCallbackTarget(gameObjectName ?? "");
#elif UNITY_ANDROID && !UNITY_EDITOR
        using (var jc = new AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
            jc.CallStatic("setInAppMessageCallbackTarget", gameObjectName ?? "");
#else
        // エディタでは未使用
#endif
    }

    /// <summary>コンテンツカードのプリフェッチ。C# で初期化完了後に呼ぶ（タイミング・surface は C# が指定）。</summary>
    public static void PrefetchContentCards(string surfacePath, string gameObjectName)
    {
#if UNITY_IOS && !UNITY_EDITOR
        _ios_aep_prefetchContentCards(surfacePath ?? "square", gameObjectName ?? "");
#elif UNITY_ANDROID && !UNITY_EDITOR
        using (var jc = new AndroidJavaClass("com.adobe.aep.unity.AEPSdkBridge"))
            jc.CallStatic("prefetchContentCards", surfacePath ?? "square", gameObjectName ?? "");
#else
        Debug.Log("AEPNativeBridge: PrefetchContentCards would run on device");
#endif
    }
}
