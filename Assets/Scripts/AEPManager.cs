using UnityEngine;
using UnityEngine.UI;
using TMPro;
using System.Runtime.InteropServices;

public class AEPManager : MonoBehaviour
{
    [SerializeField] private ParticleSystem clickEffect;
    [SerializeField] private Transform buttonTransform;
    [SerializeField] private TMP_InputField identityInputField;
    [SerializeField] private GameObject cardsSurface;
    
    [DllImport("__Internal")]
    private static extern void _ios_aep_initialize();

    [DllImport("__Internal")]
    private static extern void _ios_aep_trackAction(string action, string key, string value);
    
    [DllImport("__Internal")]
    private static extern void _ios_aep_sendEvent(string eventName, string key, string value);
    
    [DllImport("__Internal")]
    private static extern void _ios_aep_syncIdentifier(string identifierType, string identifier);
    
    [DllImport("__Internal")]
    private static extern void _ios_aep_updatePropositionsForSurfaces(string surfacePath);

    [DllImport("__Internal")]
    private static extern void _ios_aep_updateIdentities(string identifierType, string identifier);

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
    private static void Initialize()
    {
        #if UNITY_IOS && !UNITY_EDITOR
            _ios_aep_initialize();
            Debug.Log("AEP SDK initialized at app launch");
        #else
            Debug.Log("AEP SDK initialization skipped (Editor mode)");
        #endif
    }

    void Start()
    {
        // 初期化時にエフェクトの位置をボタンに合わせる
        if (clickEffect != null && buttonTransform != null)
        {
            clickEffect.transform.position = buttonTransform.position;
        }
    }

    public void SendTrackAction()
    {
        #if UNITY_IOS && !UNITY_EDITOR
            _ios_aep_trackAction("button_clicked", "user_type", "demo_user");
            Debug.Log("Native Swift call sent!");
        #else
            Debug.Log("Native code only runs on iOS device.");
        #endif
    }
    
    public void SendEvent()
    {
        if (clickEffect != null)
        {
            clickEffect.Play();
            Debug.Log("Particle effect played!");
        }

        #if UNITY_IOS && !UNITY_EDITOR
            _ios_aep_sendEvent("application.click", ["clickedObject": "SendEvent"]);
            Debug.Log("Edge.sendEvent called with Product Viewed event");
        #else
            Debug.Log("SendEvent: Edge.sendEvent would be called on iOS device");
        #endif
    }

    public void SyncIdentifier()
    {
        string crmId = "C00001"; // デフォルト値
        
        // InputFieldから値を取得
        if (identityInputField != null && !string.IsNullOrEmpty(identityInputField.text))
        {
            crmId = identityInputField.text;
        }
        
        #if UNITY_IOS && !UNITY_EDITOR
            _ios_aep_sendEvent("application.click", ["clickedObject": "SyncIdentifier", "crmId": crmId]);
            _ios_aep_syncIdentifier("idType", crmId);
            Debug.Log($"Identity.syncIdentifier called with CRM ID: {crmId}");
        #else
            Debug.Log($"SyncIdentifier: Would sync CRM ID: {crmId} on iOS device");
        #endif
    }

    public void UpdateIdentities()
    {
        string crmId = "C00001"; // デフォルト値
        
        // InputFieldから値を取得
        if (identityInputField != null && !string.IsNullOrEmpty(identityInputField.text))
        {
            crmId = identityInputField.text;
        }
        
        #if UNITY_IOS && !UNITY_EDITOR
            _ios_aep_sendEvent("application.click", ["clickedObject": "UpdateIdentities", "crmId": crmId]);
            _ios_aep_updateIdentities("extentedPersonalId", crmId);
            Debug.Log($"Identity.updateIdentities called with CRM ID: {crmId}");
        #else
            Debug.Log($"UpdateIdentities: Would update CRM ID: {crmId} on iOS device");
        #endif
    }

    public void UpdatePropositionsForSurfaces()
    {
        string surfacePath = "home#square";

        #if UNITY_IOS && !UNITY_EDITOR
            _ios_aep_sendEvent("application.click", ["clickedObject": "UpdatePropositionsForSurfaces", "surfacePath": surfacePath]);
            _ios_aep_updatePropositionsForSurfaces(surfacePath);
            Debug.Log($"Messaging.updatePropositionsForSurfaces called for: {surfacePath}");
        #else
            Debug.Log($"UpdatePropositionsForSurfaces: Would update cards surface on iOS device");
        #endif
        
        // カード表示エリアにビジュアルフィードバックを表示（オプション）
        if (cardsSurface != null)
        {
            Debug.Log("Cards surface ready to display propositions");
        }
    }
}