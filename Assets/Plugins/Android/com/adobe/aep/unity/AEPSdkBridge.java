package com.adobe.aep.unity;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.adobe.marketing.mobile.AdobeCallback;
import com.adobe.marketing.mobile.Assurance;
import com.adobe.marketing.mobile.Edge;
import com.adobe.marketing.mobile.ExperienceEvent;
import com.adobe.marketing.mobile.LoggingMode;
import com.adobe.marketing.mobile.Messaging;
import com.adobe.marketing.mobile.MobileCore;
import com.adobe.marketing.mobile.messaging.Proposition;
import com.adobe.marketing.mobile.messaging.PropositionItem;
import com.adobe.marketing.mobile.messaging.SchemaType;
import com.adobe.marketing.mobile.messaging.Surface;
import com.adobe.marketing.mobile.edge.identity.IdentityItem;
import com.adobe.marketing.mobile.edge.identity.IdentityMap;
import com.unity3d.player.UnityPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unity から AEP Android SDK を呼び出すブリッジ。iOS の AEPSdkBridge と同等の API を提供する。
 */
public class AEPSdkBridge {
    private static final String TAG = "AEPSdkBridge";
    private static boolean isInitialized = false;

    private static void sendToUnity(final String gameObjectName, final String method, final String message) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    UnityPlayer.UnitySendMessage(gameObjectName, method, message != null ? message : "");
                } catch (Exception e) {
                    Log.e(TAG, "UnitySendMessage failed", e);
                }
            }
        });
    }

    /**
     * AEP SDK を初期化。完了時に Unity の callbackMethodName に "success" または "failed" を送る。
     */
    public static void initialize(final String appId, final String gameObjectName, final String callbackMethodName) {
        if (appId == null || appId.trim().isEmpty()) {
            Log.e(TAG, "AEP SDK initialization failed: appId is empty.");
            sendToUnity(gameObjectName, callbackMethodName, "failed");
            return;
        }
        if (isInitialized) {
            sendToUnity(gameObjectName, callbackMethodName, "success");
            return;
        }

        try {
            Application app = (Application) UnityPlayer.currentActivity.getApplicationContext();
            MobileCore.setLogLevel(LoggingMode.DEBUG);
            MobileCore.initialize(app, appId.trim(), new AdobeCallback<Object>() {
                @Override
                public void call(Object o) {
                    isInitialized = true;
                    Log.d(TAG, "AEP SDK initialization completed.");
                    sendToUnity(gameObjectName, callbackMethodName, "success");
                    prefetchContentCards();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "AEP SDK initialization failed", e);
            sendToUnity(gameObjectName, callbackMethodName, "failed");
        }
    }

    private static void prefetchContentCards() {
        try {
            Surface surface = new Surface("square");
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            Messaging.updatePropositionsForSurfaces(surfaces, new AdobeCallback<Boolean>() {
                @Override
                public void call(Boolean success) {
                    sendToUnity("AEPManager", "OnContentCardsPrefetched", success != null && success ? "success" : "failed");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "prefetchContentCards failed", e);
            sendToUnity("AEPManager", "OnContentCardsPrefetched", "failed");
        }
    }

    /**
     * Assurance セッションを開始（デバッグ用）。
     */
    public static void startAssuranceSession() {
        if (!isInitialized) {
            Log.w(TAG, "SDK not initialized yet.");
            return;
        }
        Assurance.startSession();
    }

    /**
     * Edge にイベントを送信。
     */
    public static void sendEvent(String eventName, String jsonData) {
        try {
            Map<String, Object> xdmData = new HashMap<>();
            xdmData.put("eventType", eventName != null ? eventName : "");
            if (jsonData != null && !jsonData.isEmpty()) {
                try {
                    JSONObject json = new JSONObject(jsonData);
                    for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                        String key = it.next();
                        xdmData.put(key, json.get(key));
                    }
                } catch (Exception ignored) {}
            }
            ExperienceEvent event = new ExperienceEvent.Builder()
                    .setXdmSchema(xdmData)
                    .build();
            Edge.sendEvent(event, null);
        } catch (Exception e) {
            Log.e(TAG, "sendEvent failed", e);
        }
    }

    /**
     * Identity を更新。
     */
    public static void updateIdentities(String identifierType, String identifier) {
        try {
            IdentityMap map = new IdentityMap();
            map.addItem(new IdentityItem(identifier), identifierType != null ? identifierType : "");
            com.adobe.marketing.mobile.edge.identity.Identity.updateIdentities(map);
        } catch (Exception e) {
            Log.e(TAG, "updateIdentities failed", e);
        }
    }

    /**
     * コンテンツカードを取得し、JSON で Unity の callbackMethodName に送る。
     * 形式: {"cards":[{ "title","body","imageUrl","actionUrl","buttonText"}], "error":?}
     */
    public static void getContentCardsForUnity(final String surfacePath, final String gameObjectName, final String callbackMethodName) {
        if (!isInitialized) {
            sendToUnity(gameObjectName, callbackMethodName, "{\"error\":\"SDK not initialized\"}");
            return;
        }
        try {
            final Surface surface = new Surface(surfacePath != null ? surfacePath : "square");
            final List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);

            Messaging.getPropositionsForSurfaces(surfaces, new AdobeCallback<Map<Surface, List<Proposition>>>() {
                @Override
                public void call(Map<Surface, List<Proposition>> map) {
                    String jsonResult;
                    try {
                        List<Proposition> propositions = map != null ? map.get(surface) : null;
                        JSONArray cardsArray = new JSONArray();
                        if (propositions != null) {
                            for (Proposition proposition : propositions) {
                                for (PropositionItem item : proposition.getItems()) {
                                    if (item.getSchema() == SchemaType.CONTENT_CARD) {
                                        JSONObject card = contentCardItemToJson(item);
                                        if (card != null && card.length() > 0) {
                                            cardsArray.put(card);
                                        }
                                    }
                                }
                            }
                        }
                        JSONObject result = new JSONObject();
                        result.put("cards", cardsArray);
                        jsonResult = result.toString();
                    } catch (Exception e) {
                        Log.e(TAG, "getContentCardsForUnity parse error", e);
                        try {
                            JSONObject err = new JSONObject();
                            err.put("error", e.getMessage());
                            jsonResult = err.toString();
                        } catch (Exception e2) {
                            jsonResult = "{\"error\":\"Unknown error\"}";
                        }
                    }
                    sendToUnity(gameObjectName, callbackMethodName, jsonResult);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "getContentCardsForUnity failed", e);
            sendToUnity(gameObjectName, callbackMethodName, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private static JSONObject contentCardItemToJson(PropositionItem item) {
        JSONObject card = new JSONObject();
        try {
            Map<String, Object> data = item.getItemData();
            if (data == null) return card;
            Object contentObj = data.get("content");
            if (contentObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> content = (Map<String, Object>) contentObj;
                putStringFromNested(content, "title", "content", card, "title");
                putStringFromNested(content, "body", "content", card, "body");
                putStringFromNested(content, "image", "url", card, "imageUrl");
                putStringDirect(content, "actionUrl", card, "actionUrl");
                if (content.containsKey("buttons") && content.get("buttons") instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> buttons = (List<Map<String, Object>>) content.get("buttons");
                    if (buttons != null && !buttons.isEmpty()) {
                        Map<String, Object> first = buttons.get(0);
                        if (first != null) {
                            putStringFromNested(first, "text", "content", card, "buttonText");
                            if (first.containsKey("actionUrl")) {
                                Object url = first.get("actionUrl");
                                if (url != null) card.put("actionUrl", url.toString());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "contentCardItemToJson", e);
        }
        return card;
    }

    private static void putStringFromNested(Map<String, Object> parent, String key1, String key2, JSONObject target, String targetKey) {
        try {
            Object o = parent.get(key1);
            if (o instanceof Map) {
                Object v = ((Map<?, ?>) o).get(key2);
                if (v != null) target.put(targetKey, v.toString());
            }
        } catch (Exception ignored) {}
    }

    private static void putStringDirect(Map<String, Object> source, String sourceKey, JSONObject target, String targetKey) {
        try {
            Object v = source.get(sourceKey);
            if (v != null) target.put(targetKey, v.toString());
        } catch (Exception ignored) {}
    }

    /**
     * ネイティブテンプレートでコンテンツカードを表示（Android では getContentCardsForUnity と同等の取得のみ行い、UI は Unity 側で表示）。
     */
    public static void showContentCardsWithTemplates(String surfacePath, String templateStyle) {
        Log.d(TAG, "showContentCardsWithTemplates: " + surfacePath + ", " + templateStyle + " (Android: use Scroll View in Unity)");
        // Android ではネイティブドロワー UI は別実装が必要なため、ここではログのみ。Unity の Scroll View で表示する運用とする。
    }

    /**
     * Proposition を手動更新。完了時に OnPropositionsUpdated へ "success:surfacePath" / "failed:surfacePath" / "timeout:surfacePath" を送る。
     */
    public static void updatePropositionsManually(final String surfacePath) {
        if (!isInitialized) {
            sendToUnity("AEPManager", "OnPropositionsUpdated", "failed:" + (surfacePath != null ? surfacePath : ""));
            return;
        }
        try {
            final Surface surface = new Surface(surfacePath != null ? surfacePath : "square");
            final List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);

            Messaging.updatePropositionsForSurfaces(surfaces, new AdobeCallback<Boolean>() {
                @Override
                public void call(Boolean success) {
                    String result = Boolean.TRUE.equals(success) ? "success:" : "failed:";
                    result += (surfacePath != null ? surfacePath : "square");
                    sendToUnity("AEPManager", "OnPropositionsUpdated", result);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "updatePropositionsManually failed", e);
            sendToUnity("AEPManager", "OnPropositionsUpdated", "failed:" + (surfacePath != null ? surfacePath : ""));
        }
    }
}
