package com.adobe.aep.unity;

import android.app.Application;
import android.app.Activity;
import android.os.Bundle;
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
import org.json.JSONException;
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
    private static Application.ActivityLifecycleCallbacks activityCallback;

    /** In-App Message の JS コールバック先（iOS の setInAppMessageCallbackTarget と同一）。 */
    private static String inAppMessageCallbackTarget = "AEPManager";

    public static void setInAppMessageCallbackTarget(String name) {
        inAppMessageCallbackTarget = normalizeTarget(name);
    }

    static String getInAppMessageCallbackTarget() {
        return inAppMessageCallbackTarget != null ? inAppMessageCallbackTarget : "AEPManager";
    }

    /** In-App の JS から Unity に送る際に使用（OnInAppMessageAction）。 */
    public static void sendToUnityInAppCallback(String target, String payload) {
        sendToUnity(target, "OnInAppMessageAction", payload);
    }

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

    private static String normalizeSurface(String s) {
        return (s != null && !s.trim().isEmpty()) ? s.trim() : "square";
    }

    private static String normalizeTarget(String s) {
        return (s != null && !s.trim().isEmpty()) ? s.trim() : "AEPManager";
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
                    onSdkInitialized(app, gameObjectName, callbackMethodName);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "AEP SDK initialization failed", e);
            sendToUnity(gameObjectName, callbackMethodName, "failed");
        }
    }

    /**
     * SDK 初期化完了時の共通処理（Unity からの initialize と AEPApplication の両方から使用）。
     * デリゲート登録・Activity 設定・Unity への通知を行う。
     */
    private static void onSdkInitialized(Application app, String gameObjectName, String callbackMethodName) {
        isInitialized = true;
        Log.d(TAG, "AEP SDK initialization completed.");
        registerMessagingDelegateIfAvailable();
        setCurrentActivityForInApp(UnityPlayer.currentActivity);
        registerActivityLifecycleForInApp(app);
        if (gameObjectName != null && callbackMethodName != null) {
            sendToUnity(gameObjectName, callbackMethodName, "success");
        }
    }

    /**
     * カスタム Application（AEPApplication）が Application.onCreate で SDK を初期化した場合に呼ぶ。
     * デリゲート登録と ActivityLifecycleCallbacks 登録のみ行い、Unity への通知は行わない
     * （Unity は後から initialize() を呼び、その時に success を受け取る）。
     */
    static void onSdkInitializedByApplication(Application app) {
        if (isInitialized) return;
        isInitialized = true;
        Log.d(TAG, "AEP SDK initialization completed (by AEPApplication.onCreate).");
        registerMessagingDelegateIfAvailable();
        registerActivityLifecycleForInApp(app);
        // Unity には AEPSdkBridge.initialize() が呼ばれたときに success を送る
    }

    /**
     * In-App Message を表示するためにデリゲートを登録する。
     * 未登録だと Android では In-App Message が表示されない。
     * 3.x では PresentationDelegate + ServiceProvider.uiService を使用。旧 API の MessagingDelegate はリフレクションでフォールバック。
     */
    private static void registerMessagingDelegateIfAvailable() {
        // 1) 新 API: PresentationDelegate + ServiceProvider.getInstance().uiService.setPresentationDelegate(...)
        try {
            Class<?> presentationDelegateClass = Class.forName("com.adobe.marketing.mobile.services.ui.PresentationDelegate");
            Class<?> serviceProviderClass = Class.forName("com.adobe.marketing.mobile.services.ServiceProvider");
            Object instance = serviceProviderClass.getMethod("getInstance").invoke(null);
            Object uiService = null;
            for (String getterName : new String[]{"getUiService", "getUIService"}) {
                try {
                    java.lang.reflect.Method getter = serviceProviderClass.getMethod(getterName);
                    uiService = getter.invoke(instance);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (uiService == null) {
                Log.w(TAG, "ServiceProvider.getUiService()/getUIService() returned null or not found.");
            } else {
                final Class<?> pDelegateClass = presentationDelegateClass;
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                        presentationDelegateClass.getClassLoader(),
                        new Class<?>[]{presentationDelegateClass},
                        (proxy1, method, args) -> {
                            if ("canShow".equals(method.getName())) {
                                return true;
                            }
                            if ("onShow".equals(method.getName()) && args != null && args.length >= 1) {
                                final Object presentable = args[0];
                                Handler mainHandler = new Handler(Looper.getMainLooper());
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        attachInAppWebViewHandling(presentable);
                                    }
                                });
                                return null;
                            }
                            return null;
                        });
                uiService.getClass().getMethod("setPresentationDelegate", presentationDelegateClass).invoke(uiService, proxy);
                Log.d(TAG, "PresentationDelegate registered. In-App Message will be displayed (with URL/JS custom handling).");
                return;
            }
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "PresentationDelegate/ServiceProvider not found, trying legacy MessagingDelegate: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "setPresentationDelegate failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }

        // 2) 旧 API: MessagingDelegate + MobileCore.setMessagingDelegate(...)
        try {
            Class<?> delegateClass = null;
            for (String pkg : new String[]{
                    "com.adobe.marketing.mobile.services.MessagingDelegate",
                    "com.adobe.marketing.mobile.MessagingDelegate"
            }) {
                try {
                    delegateClass = Class.forName(pkg);
                    break;
                } catch (ClassNotFoundException ignored) {}
            }
            if (delegateClass == null) {
                Log.w(TAG, "MessagingDelegate not found. In-App Message may not display.");
                return;
            }
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    delegateClass.getClassLoader(),
                    new Class<?>[]{delegateClass},
                    (proxy1, method, args) -> {
                        if ("shouldShowMessage".equals(method.getName())) {
                            return true;
                        }
                        return null;
                    });
            MobileCore.class.getMethod("setMessagingDelegate", delegateClass).invoke(null, proxy);
            Log.d(TAG, "MessagingDelegate registered. In-App Message will be displayed.");
        } catch (Exception e) {
            Log.w(TAG, "setMessagingDelegate failed (In-App Message may not show): " + e.getMessage());
        }
    }

    /**
     * View またはその子階層から最初の WebView を探す（Message.getView() がコンテナの場合用）。
     */
    private static android.webkit.WebView findWebViewInHierarchy(android.view.View view) {
        if (view instanceof android.webkit.WebView) return (android.webkit.WebView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.webkit.WebView w = findWebViewInHierarchy(vg.getChildAt(i));
                if (w != null) return w;
            }
        }
        return null;
    }

    /**
     * PresentationDelegate.onShow で呼ぶ。Presentable から Message と WebView を取得し、
     * adbinapp / http(s) の URL インターセプトと AEPInAppCallback の JS インターフェースを設定する。
     * SDK が先に WebViewClient を設定する可能性があるため、短い遅延後にアタッチして上書きする。
     */
    private static void attachInAppWebViewHandling(final Object presentable) {
        if (presentable == null) return;
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final int[] delays = new int[]{150, 400, 700};
        for (int i = 0; i < delays.length; i++) {
            final int delay = delays[i];
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (doAttachInAppWebViewHandling(presentable)) {
                        Log.d(TAG, "In-App WebView attached at delay " + delay + "ms");
                    }
                }
            }, delay);
        }
    }

    /** @return true if WebView にアタッチできた */
    private static boolean doAttachInAppWebViewHandling(Object presentable) {
        if (presentable == null) return false;
        Object message = getMessageFromPresentable(presentable);
        if (message == null) {
            Log.w(TAG, "attachInAppWebViewHandling: could not get Message from presentable (presentable class: " + presentable.getClass().getName() + ")");
            return false;
        }
        android.view.View rootView = getViewFromMessageOrPresentable(message, presentable);
        if (rootView == null) {
            Log.w(TAG, "attachInAppWebViewHandling: no View from Message or Presentable (message: " + message.getClass().getName() + ", presentable: " + (presentable != null ? presentable.getClass().getName() : "null") + ")");
            return false;
        }
        try {
            android.webkit.WebView webView = findWebViewInHierarchy(rootView);
            if (webView == null) {
                Log.w(TAG, "attachInAppWebViewHandling: No WebView in view hierarchy.");
                return false;
            }

            webView.setWebViewClient(InAppNavigationHandler.createUrlInterceptClient(message));
            if (android.os.Build.VERSION.SDK_INT >= 17) {
                webView.addJavascriptInterface(
                        InAppNavigationHandler.createJsInterface(message, getInAppMessageCallbackTarget()),
                        InAppNavigationHandler.JAVASCRIPT_INTERFACE_NAME);
            }
            Log.d(TAG, "In-App WebView: URL intercept and AEPInAppCallback JS interface attached.");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "attachInAppWebViewHandling failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Message または Presentable からルート View を取得。SDK によって View が Message 側か Presentable 側にある。
     * PresentableMessageMapper$InternalMessage の場合は getDeclaredMethods/Fields、getWindow、Activity 内 WebView 探索を行う。
     */
    private static android.view.View getViewFromMessageOrPresentable(Object message, Object presentable) {
        // InAppMessagePresentable の場合は getPresentation() で取得したオブジェクトにも View がある
        Object presentation = null;
        if (presentable != null) {
            try {
                java.lang.reflect.Method getPres = presentable.getClass().getMethod("getPresentation");
                presentation = getPres.invoke(presentable);
            } catch (Exception ignored) {}
        }
        String[] getters = new String[]{"getView", "getWebView", "getContentView", "getRootView"};
        for (Object source : new Object[]{message, presentable, presentation}) {
            if (source == null) continue;
            for (String getter : getters) {
                try {
                    java.lang.reflect.Method m = source.getClass().getMethod(getter);
                    Object v = m.invoke(source);
                    if (v instanceof android.view.View) return (android.view.View) v;
                } catch (NoSuchMethodException ignored) {
                } catch (Exception e) {
                    Log.d(TAG, "getViewFromMessageOrPresentable " + source.getClass().getSimpleName() + "." + getter + ": " + e.getMessage());
                }
            }
            android.view.View fromWindow = getViewFromWindow(source);
            if (fromWindow != null) return fromWindow;
            for (java.lang.reflect.Method m : source.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (!android.view.View.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    Object v = m.invoke(source);
                    if (v instanceof android.view.View) return (android.view.View) v;
                } catch (Exception ignored) {}
            }
            android.view.View v = getViewViaReflectionDeep(source);
            if (v != null) return v;
            v = findViewInObjectGraph(source, 0, 5, new java.util.HashSet<Object>());
            if (v != null) return v;
        }
        return findInAppWebViewFromActivity();
    }

    /** オブジェクトグラフを再帰的に走査し、任意の深さにある View を 1 つ返す。visited で循環を避ける。 */
    private static android.view.View findViewInObjectGraph(Object obj, int depth, int maxDepth, java.util.Set<Object> visited) {
        if (obj == null || depth > maxDepth) return null;
        if (visited.contains(obj)) return null;
        if (obj instanceof android.view.View) return (android.view.View) obj;
        Class<?> c = obj.getClass();
        if (c.isPrimitive() || c == String.class || c == Integer.class || c == Long.class || c == Boolean.class || Number.class.isAssignableFrom(c) || c == Class.class || c.isEnum()) return null;
        visited.add(obj);
        try {
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val instanceof android.view.View) return (android.view.View) val;
                        if (!visited.contains(val)) {
                            android.view.View v = findViewInObjectGraph(val, depth + 1, maxDepth, visited);
                            if (v != null) return v;
                        }
                    } catch (Exception ignored) {}
                }
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length != 0 || m.getReturnType() == void.class) continue;
                    try {
                        m.setAccessible(true);
                        Object val = m.invoke(obj);
                        if (val instanceof android.view.View) return (android.view.View) val;
                        if (val != null && !visited.contains(val)) {
                            android.view.View v = findViewInObjectGraph(val, depth + 1, maxDepth, visited);
                            if (v != null) return v;
                        }
                    } catch (Exception ignored) {}
                }
                c = c.getSuperclass();
            }
        } finally {
            visited.remove(obj);
        }
        return null;
    }

    /** getWindow() を持っていれば getDecorView() から WebView を探す（Dialog 表示時用）。getDialog() や Dialog 型を返すメソッド・フィールドも試す。 */
    private static android.view.View getViewFromWindow(Object source) {
        Object windowOwner = source;
        try {
            java.lang.reflect.Method getDialog = source.getClass().getMethod("getDialog");
            Object dialog = getDialog.invoke(source);
            if (dialog != null) windowOwner = dialog;
        } catch (Exception ignored) {}
        if (windowOwner == source) {
            Class<?> c = source.getClass();
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length != 0) continue;
                    Class<?> ret = m.getReturnType();
                    if (android.app.Dialog.class.isAssignableFrom(ret) || (ret.getName().contains("Dialog"))) {
                        try {
                            m.setAccessible(true);
                            Object dialog = m.invoke(source);
                            if (dialog != null) { windowOwner = dialog; break; }
                        } catch (Exception ignored) {}
                    }
                }
                if (windowOwner != source) break;
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!android.app.Dialog.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object dialog = f.get(source);
                        if (dialog != null) { windowOwner = dialog; break; }
                    } catch (Exception ignored) {}
                }
                if (windowOwner != source) break;
                c = c.getSuperclass();
            }
        }
        try {
            java.lang.reflect.Method getWindow = windowOwner.getClass().getMethod("getWindow");
            getWindow.setAccessible(true);
            Object window = getWindow.invoke(windowOwner);
            if (window == null) return null;
            java.lang.reflect.Method getDecorView = window.getClass().getMethod("getDecorView");
            Object decor = getDecorView.invoke(window);
            if (decor instanceof android.view.View) {
                android.webkit.WebView w = findWebViewInHierarchy((android.view.View) decor);
                if (w != null) return w;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** リフレクションで getDeclaredMethods / getDeclaredFields から View を取得（クラス階層をたどる）。 */
    private static android.view.View getViewViaReflectionDeep(Object source) {
        Class<?> c = source.getClass();
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (!android.view.View.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(source);
                    if (v instanceof android.view.View) return (android.view.View) v;
                } catch (Exception ignored) {}
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!android.view.View.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(source);
                    if (v instanceof android.view.View) return (android.view.View) v;
                } catch (Exception ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 他に View が取れない場合、Activity の DecorView 配下で In-App 用と推測される WebView を 1 つ返す。 */
    private static android.view.View findInAppWebViewFromActivity() {
        Activity act = UnityPlayer.currentActivity;
        if (act == null || act.getWindow() == null) return null;
        android.view.View root = act.getWindow().getDecorView();
        if (root == null) return null;
        final android.webkit.WebView[] withParent = new android.webkit.WebView[1];
        final java.util.List<android.webkit.WebView> visible = new java.util.ArrayList<android.webkit.WebView>(2);
        collectWebViewsInRoot(root, withParent, visible);
        if (withParent[0] != null) {
            Log.d(TAG, "findInAppWebViewFromActivity: found WebView with In-App-like parent.");
            return withParent[0];
        }
        if (visible.size() == 1) {
            Log.d(TAG, "findInAppWebViewFromActivity: using single visible WebView.");
            return visible.get(0);
        }
        Log.d(TAG, "findInAppWebViewFromActivity: no match (visible WebViews: " + visible.size() + ")");
        return null;
    }

    private static void collectWebViewsInRoot(android.view.View view, android.webkit.WebView[] withInAppParent, java.util.List<android.webkit.WebView> visible) {
        if (view instanceof android.webkit.WebView) {
            android.webkit.WebView w = (android.webkit.WebView) view;
            if (w.isShown()) visible.add(w);
            if (hasInAppMessageParent(view)) withInAppParent[0] = w;
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++)
                collectWebViewsInRoot(vg.getChildAt(i), withInAppParent, visible);
        }
    }

    private static boolean hasInAppMessageParent(android.view.View view) {
        android.view.ViewParent p = view.getParent();
        while (p instanceof android.view.View) {
            String name = p.getClass().getName();
            if (name.contains("Message") || name.contains("Presentable") || name.contains("Fullscreen") || name.contains("InApp")
                    || name.contains("Adobe") || name.contains("adobe") || name.contains("AEP") || name.contains("Messaging"))
                return true;
            p = ((android.view.View) p).getParent();
        }
        return false;
    }

    /**
     * Presentable から Message を取得。MessagingUtils.getMessageForPresentable のシグネチャが
     * SDK バージョンで異なるため、リフレクションで複数パターンを試し、失敗時は presentable の getter で直接取得する。
     */
    private static Object getMessageFromPresentable(Object presentable) {
        if (presentable == null) return null;
        Class<?> presentableClass = presentable.getClass();

        // 1) MessagingUtils.getMessageForPresentable(presentable) — 実クラスでメソッドを探す
        for (String cn : new String[]{"com.adobe.marketing.mobile.messaging.MessagingUtils", "com.adobe.marketing.mobile.MessagingUtils"}) {
            try {
                Class<?> utilsClass = Class.forName(cn);
                for (java.lang.reflect.Method m : utilsClass.getMethods()) {
                    if (!"getMessageForPresentable".equals(m.getName()) || m.getParameterTypes().length != 1) continue;
                    Class<?> param = m.getParameterTypes()[0];
                    if (!param.isAssignableFrom(presentableClass)) continue;
                    try {
                        Object msg = m.invoke(null, presentable);
                        if (msg != null) return msg;
                    } catch (Exception invE) {
                        Log.d(TAG, "getMessageForPresentable invoke failed: " + invE.getMessage());
                    }
                }
                // Object.class で試す（従来の呼び方）
                try {
                    java.lang.reflect.Method m = utilsClass.getMethod("getMessageForPresentable", Object.class);
                    Object msg = m.invoke(null, presentable);
                    if (msg != null) return msg;
                } catch (NoSuchMethodException ignored) {}
            } catch (ClassNotFoundException ignored) {
            } catch (Exception e) {
                Log.d(TAG, "getMessageForPresentable via " + cn + " failed: " + e.getMessage());
            }
        }

        // 2) presentable から直接取得: getMessage(), getParent(), getItem(), getInAppMessage() 等
        for (String getter : new String[]{"getMessage", "getParent", "getItem", "getInAppMessage", "getFullscreenMessage"}) {
            try {
                java.lang.reflect.Method m = presentableClass.getMethod(getter);
                Object candidate = m.invoke(presentable);
                if (candidate == null) continue;
                for (String vg : new String[]{"getView", "getWebView"}) {
                    try {
                        Object v = candidate.getClass().getMethod(vg).invoke(candidate);
                        if (v instanceof android.view.View) return candidate;
                    } catch (NoSuchMethodException ignored) {}
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * SDK の「現在の Activity」を Unity の Activity に設定する。
     * logcat: "Services/AEPPresentable - Current activity is null. Cannot show presentable." の対策。
     * MobileCore.initialize は Application のみ受け取り、Activity は ActivityLifecycleCallbacks で取得するが、
     * Unity では初期化が Activity onResume 後になることがあり、SDK が current activity を未取得のままになる。
     * UiService の実装クラスで Activity を受け取る setter（setCurrentActivity / setActivity または単一引数 Activity のメソッド）をリフレクションで呼ぶ。
     */
    private static void setCurrentActivityForInApp(final Activity activity) {
        if (activity == null) return;
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> serviceProviderClass = Class.forName("com.adobe.marketing.mobile.services.ServiceProvider");
                    Object instance = serviceProviderClass.getMethod("getInstance").invoke(null);
                    Object uiService = null;
                    for (String getterName : new String[]{"getUiService", "getUIService"}) {
                        try {
                            java.lang.reflect.Method getter = serviceProviderClass.getMethod(getterName);
                            uiService = getter.invoke(instance);
                            break;
                        } catch (NoSuchMethodException ignored) {}
                    }
                    if (uiService == null) return;

                    // 1) 既知のメソッド名で public から試す
                    for (String methodName : new String[]{"setCurrentActivity", "setActivity"}) {
                        try {
                            java.lang.reflect.Method setter = uiService.getClass().getMethod(methodName, Activity.class);
                            setter.invoke(uiService, activity);
                            Log.d(TAG, "Set current activity for In-App Message: " + methodName + " succeeded.");
                            return;
                        } catch (NoSuchMethodException ignored) {}
                    }

                    // 2) 実装クラスの declared メソッド（Kotlin internal 等）を試す
                    Class<?> implClass = uiService.getClass();
                    for (String methodName : new String[]{"setCurrentActivity", "setActivity"}) {
                        try {
                            java.lang.reflect.Method setter = implClass.getDeclaredMethod(methodName, Activity.class);
                            setter.setAccessible(true);
                            setter.invoke(uiService, activity);
                            Log.d(TAG, "Set current activity for In-App Message: " + methodName + " (declared) succeeded.");
                            return;
                        } catch (NoSuchMethodException ignored) {}
                    }

                    // 3) Activity を単一引数に取るメソッドを探索して呼ぶ（set*Activity* など）
                    for (java.lang.reflect.Method m : implClass.getDeclaredMethods()) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length != 1 || !Activity.class.isAssignableFrom(params[0])) continue;
                        String name = m.getName().toLowerCase();
                        if (!name.contains("activity") && !name.contains("current")) continue;
                        try {
                            m.setAccessible(true);
                            m.invoke(uiService, activity);
                            Log.d(TAG, "Set current activity for In-App Message: " + m.getName() + " succeeded.");
                            return;
                        } catch (Exception e) {
                            Log.d(TAG, "setCurrentActivityForInApp invoke " + m.getName() + ": " + e.getMessage());
                        }
                    }

                    // 4) currentActivity フィールド（Kotlin の backing field 等）を直接設定
                    for (java.lang.reflect.Field f : implClass.getDeclaredFields()) {
                        if (!Activity.class.isAssignableFrom(f.getType())) continue;
                        if (!f.getName().toLowerCase().contains("activity")) continue;
                        try {
                            f.setAccessible(true);
                            f.set(uiService, activity);
                            Log.d(TAG, "Set current activity for In-App Message: field " + f.getName() + " succeeded.");
                            return;
                        } catch (Exception e) {
                            Log.d(TAG, "setCurrentActivityForInApp field " + f.getName() + ": " + e.getMessage());
                        }
                    }
                    Log.d(TAG, "setCurrentActivityForInApp: no setter/field found on UiService for Activity.");
                } catch (Exception e) {
                    Log.d(TAG, "setCurrentActivityForInApp: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Application に ActivityLifecycleCallbacks を登録し、onActivityResumed のたびに
     * 現在の Activity を SDK に渡す。Unity では初期化が onResume 後になるため、SDK が
     * 自前のコールバックで Activity を取得できていない場合の対策。
     */
    private static void registerActivityLifecycleForInApp(final Application app) {
        if (app == null || activityCallback != null) return;
        try {
            activityCallback = new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    setCurrentActivityForInApp(activity);
                }
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
                @Override
                public void onActivityStarted(Activity activity) {}
                @Override
                public void onActivityPaused(Activity activity) {}
                @Override
                public void onActivityStopped(Activity activity) {}
                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override
                public void onActivityDestroyed(Activity activity) {}
            };
            app.registerActivityLifecycleCallbacks(activityCallback);
            Log.d(TAG, "Registered ActivityLifecycleCallbacks for In-App Message current activity.");
        } catch (Exception e) {
            Log.w(TAG, "registerActivityLifecycleForInApp failed: " + e.getMessage());
        }
    }
    /**
     * コンテンツカードをプリフェッチ。C# が初期化完了後に surfacePath / gameObjectName を指定して呼ぶ。
     */
    public static void prefetchContentCards(final String surfacePath, final String gameObjectName) {
        String target = normalizeTarget(gameObjectName);
        try {
            Surface surface = new Surface(normalizeSurface(surfacePath));
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            Messaging.updatePropositionsForSurfaces(surfaces, new AdobeCallback<Boolean>() {
                @Override
                public void call(Boolean success) {
                    sendToUnity(target, "OnContentCardsPrefetched", success != null && success ? "success" : "failed");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "prefetchContentCards failed", e);
            sendToUnity(gameObjectName, "OnContentCardsPrefetched", "failed");
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
     * Unity の JNI 呼び出しは Android のメインスレッドではなく Unity スレッドで実行されるため、
     * Edge.sendEvent をメインスレッドで実行する。AEP のイベントハブ／Messaging 拡張が
     * メインスレッドでディスパッチされたイベントのみを正しく評価するため、トリガーが
     * 適用されない事象を避ける。
     */
    public static void sendEvent(final String eventName, final String jsonData) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, Object> xdmData = new HashMap<>();
                    xdmData.put("eventType", eventName != null ? eventName : "");
                    if (jsonData != null && !jsonData.isEmpty()) {
                        try {
                            JSONObject json = new JSONObject(jsonData);
                            for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                                String key = it.next();
                                Object val = json.get(key);
                                // Messaging のトリガー評価で XDM 値が正しく比較されるよう、プリミティブに正規化
                                xdmData.put(key, jsonValueToObject(val));
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
        });
    }

    /** JSON の値を Edge XDM で扱いやすい Object に正規化（トリガー条件比較のため） */
    private static Object jsonValueToObject(Object val) {
        if (val == null || val == JSONObject.NULL) return null;
        if (val instanceof String || val instanceof Number || val instanceof Boolean) return val;
        if (val instanceof JSONObject) {
            Map<String, Object> map = new HashMap<>();
            JSONObject jo = (JSONObject) val;
            try {
                for (java.util.Iterator<String> it = jo.keys(); it.hasNext(); ) {
                    String k = it.next();
                    map.put(k, jsonValueToObject(jo.get(k)));
                }
            } catch (JSONException e) {
                Log.w(TAG, "jsonValueToObject JSONObject", e);
            }
            return map;
        }
        if (val instanceof JSONArray) {
            List<Object> list = new ArrayList<>();
            JSONArray ja = (JSONArray) val;
            try {
                for (int i = 0; i < ja.length(); i++) {
                    list.add(jsonValueToObject(ja.get(i)));
                }
            } catch (JSONException e) {
                Log.w(TAG, "jsonValueToObject JSONArray", e);
            }
            return list;
        }
        return val.toString();
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
     * コンテンツカードを取得し、AJO 生データ（getItemData）を JSON で Unity に送る。フラット化は C# 側で共通処理。
     * 形式: {"cards":[{"content":{...}}],"error":?}
     */
    public static void getContentCardsForUnity(final String surfacePath, final String gameObjectName, final String callbackMethodName) {
        final String target = normalizeTarget(gameObjectName);
        final String path = normalizeSurface(surfacePath);
        if (!isInitialized) {
            sendToUnity(target, callbackMethodName, "{\"error\":\"SDK not initialized\"}");
            return;
        }
        try {
            final Surface surface = new Surface(path);
            final List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);

            Messaging.getPropositionsForSurfaces(surfaces, new AdobeCallback<Map<Surface, List<Proposition>>>() {
                @Override
                public void call(Map<Surface, List<Proposition>> map) {
                    String jsonResult;
                    try {
                        List<Proposition> propositions = map != null ? map.get(surface) : null;
                        List<Map<String, Object>> rawCards = new ArrayList<>();
                        if (propositions != null) {
                            for (Proposition proposition : propositions) {
                                for (PropositionItem item : proposition.getItems()) {
                                    if (item.getSchema() == SchemaType.CONTENT_CARD) {
                                        Map<String, Object> data = item.getItemData();
                                        if (data != null) rawCards.add(data);
                                    }
                                }
                            }
                        }
                        JSONObject result = new JSONObject();
                        result.put("cards", listToJsonArray(rawCards));
                        result.put("error", JSONObject.NULL);
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
                    sendToUnity(target, callbackMethodName, jsonResult);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "getContentCardsForUnity failed", e);
            sendToUnity(target, callbackMethodName, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /** Map/List を JSON に変換（C# 側でパースするため生データのまま送る） */
    @SuppressWarnings("unchecked")
    private static JSONArray listToJsonArray(List<Map<String, Object>> list) throws JSONException {
        JSONArray arr = new JSONArray();
        for (Map<String, Object> m : list) arr.put(mapToJsonObject(m));
        return arr;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject mapToJsonObject(Map<String, Object> map) throws JSONException {
        JSONObject jo = new JSONObject();
        if (map == null) return jo;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            if (v == null) jo.put(e.getKey(), JSONObject.NULL);
            else if (v instanceof Map) jo.put(e.getKey(), mapToJsonObject((Map<String, Object>) v));
            else if (v instanceof List) jo.put(e.getKey(), listToJsonArrayGeneric((List<?>) v));
            else if (v instanceof String) jo.put(e.getKey(), (String) v);
            else if (v instanceof Number) jo.put(e.getKey(), (Number) v);
            else if (v instanceof Boolean) jo.put(e.getKey(), (Boolean) v);
            else jo.put(e.getKey(), v.toString());
        }
        return jo;
    }

    @SuppressWarnings("unchecked")
    private static JSONArray listToJsonArrayGeneric(List<?> list) throws JSONException {
        JSONArray arr = new JSONArray();
        for (Object o : list) {
            if (o == null) arr.put(JSONObject.NULL);
            else if (o instanceof Map) arr.put(mapToJsonObject((Map<String, Object>) o));
            else if (o instanceof List) arr.put(listToJsonArrayGeneric((List<?>) o));
            else if (o instanceof String) arr.put((String) o);
            else if (o instanceof Number) arr.put((Number) o);
            else if (o instanceof Boolean) arr.put((Boolean) o);
            else arr.put(o.toString());
        }
        return arr;
    }

    /**
     * ネイティブテンプレートでコンテンツカードを表示（Android では getContentCardsForUnity と同等の取得のみ行い、UI は Unity 側で表示）。
     */
    public static void showContentCardsWithTemplates(String surfacePath, String templateStyle) {
        Log.d(TAG, "showContentCardsWithTemplates: " + surfacePath + ", " + templateStyle + " (Android: use Scroll View in Unity)");
        // Android ではネイティブドロワー UI は別実装が必要なため、ここではログのみ。Unity の Scroll View で表示する運用とする。
    }

    /**
     * Proposition を手動更新。完了時に gameObjectName の OnPropositionsUpdated へ "success:surfacePath" / "failed:surfacePath" を送る。
     * getContentCardsForUnity と同様に surface / target は共通ヘルパーで正規化。
     */
    public static void updatePropositionsManually(final String surfacePath, final String gameObjectName) {
        final String target = normalizeTarget(gameObjectName);
        final String path = normalizeSurface(surfacePath);
        if (!isInitialized) {
            sendToUnity(target, "OnPropositionsUpdated", "failed:" + path);
            return;
        }
        try {
            final Surface surface = new Surface(path);
            final List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);

            Messaging.updatePropositionsForSurfaces(surfaces, new AdobeCallback<Boolean>() {
                @Override
                public void call(Boolean success) {
                    String result = Boolean.TRUE.equals(success) ? "success:" : "failed:";
                    result += path;
                    sendToUnity(target, "OnPropositionsUpdated", result);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "updatePropositionsManually failed", e);
            sendToUnity(target, "OnPropositionsUpdated", "failed:" + path);
        }
    }
}
