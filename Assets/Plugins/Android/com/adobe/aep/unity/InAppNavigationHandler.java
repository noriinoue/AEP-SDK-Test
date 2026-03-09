package com.adobe.aep.unity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.unity3d.player.UnityPlayer;

import java.lang.reflect.Method;

/**
 * In-App Message 内の URL クリックを iOS と同様に振り分ける。
 * - adbinapp://dismiss?interaction=cancel → 閉じるのみ
 * - adbinapp://dismiss?interaction=clicked&link=... → 外部ブラウザで開く
 * - adbinapp://dismiss?interaction=webview&link=... → Chrome Custom Tabs で開く
 * - http(s):// 直接 → Chrome Custom Tabs で開く
 *
 * Unity へのコールバック先は AEPSdkBridge.getInAppMessageCallbackTarget() で取得。
 */
public final class InAppNavigationHandler {
    private static final String TAG = "InAppNav";

    /** Custom Tabs 用 Intent extra（SESSION が null でも Chrome が Custom Tab として扱う） */
    private static final String EXTRA_SESSION = "android.support.customtabs.extra.SESSION";

    /**
     * URL を解釈し、処理した場合は true と interaction 文字列を返す。
     * 呼び出し側で message.dismiss() と message.track(interaction) を行う。
     */
    public static Result handleUrl(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return new Result(false, "webview");
        }
        urlString = urlString.trim();
        Uri uri = Uri.parse(urlString);
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";

        if ("adbinapp".equals(scheme)) {
            String interaction = uri.getQueryParameter("interaction");
            if (interaction == null) interaction = "webview";

            if ("cancel".equals(interaction)) {
                return new Result(true, "cancel");
            }

            String link = uri.getQueryParameter("link");
            if (link == null) link = uri.getQueryParameter("target");
            if (link != null) link = link.trim();
            if (link == null || link.isEmpty()) {
                return new Result(true, interaction);
            }

            Uri linkUri = Uri.parse(link);
            String linkScheme = linkUri.getScheme() != null ? linkUri.getScheme().toLowerCase() : "";
            String loadUrl = null;
            if ("adbinapp".equals(linkScheme)) {
                loadUrl = linkUri.buildUpon().scheme("https").build().toString();
            } else if ("https".equals(linkScheme) || "http".equals(linkScheme)) {
                loadUrl = link;
            }

            if ("clicked".equals(interaction)) {
                if (loadUrl != null) {
                    openExternalBrowser(loadUrl);
                }
                return new Result(true, "clicked");
            }
            if ("webview".equals(interaction) && loadUrl != null) {
                openCustomTab(loadUrl);
                return new Result(true, "webview");
            }
            return new Result(true, interaction);
        }

        if ("https".equals(scheme) || "http".equals(scheme)) {
            openCustomTab(urlString);
            return new Result(true, "webview");
        }
        return new Result(false, "webview");
    }

    /** デフォルトブラウザで開く（ACTION_VIEW）。 */
    public static void openExternalBrowser(String url) {
        try {
            Activity act = UnityPlayer.currentActivity;
            if (act == null) return;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            act.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "openExternalBrowser failed", e);
        }
    }

    /**
     * Chrome Custom Tabs で開く。Chrome が入っていれば Custom Tab、なければ通常のブラウザで開く。
     * 追加ライブラリ不要の低レベル API（SESSION extra 付き Intent）を使用。
     */
    public static void openCustomTab(String url) {
        Activity activity = UnityPlayer.currentActivity;
        if (activity == null) return;
        try {
            Uri uri = Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                Bundle extras = new Bundle();
                extras.putBinder(EXTRA_SESSION, null);
                intent.putExtras(extras);
            }
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "openCustomTab failed, falling back to browser", e);
            openExternalBrowser(url);
        }
    }

    /**
     * In-App の WebView に設定するクライアント。adbinapp および http(s) をインターセプトし、
     * 処理した場合は message.dismiss() と message.track(interaction) を呼ぶ。
     * messageRef は AEP の Message インスタンス（リフレクションで dismiss/track を呼ぶ）。
     */
    public static android.webkit.WebViewClient createUrlInterceptClient(final Object messageRef) {
        return new android.webkit.WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null || url.trim().isEmpty()) return false;
                Log.d(TAG, "shouldOverrideUrlLoading(String): " + url);
                Result r = handleUrl(url);
                if (r.handled) {
                    Log.d(TAG, "handled interaction=" + r.interaction);
                    dismissAndTrack(messageRef, r.interaction);
                    return true;
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                String url = request.getUrl().toString();
                Log.d(TAG, "shouldOverrideUrlLoading(Request): " + url);
                Result r = handleUrl(url);
                if (r.handled) {
                    Log.d(TAG, "handled interaction=" + r.interaction);
                    dismissAndTrack(messageRef, r.interaction);
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * Message に対して dismiss() と track(interaction, .interact) をリフレクションで実行。
     * InternalMessage は dismiss(boolean) を持たない場合があるため、dismiss() 無引数も試す。
     */
    public static void dismissAndTrack(Object messageRef, String interaction) {
        if (messageRef == null) return;
        try {
            invokeDismiss(messageRef);
            trackOnly(messageRef, interaction != null ? interaction : "click");
        } catch (Exception e) {
            Log.e(TAG, "dismissAndTrack failed", e);
        }
    }

    /** dismiss() をリフレクションで呼ぶ。dismiss() または dismiss(boolean) を試す。 */
    private static void invokeDismiss(Object messageRef) {
        Class<?> c = messageRef.getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"dismiss".equals(m.getName())) continue;
                Class<?>[] params = m.getParameterTypes();
                try {
                    m.setAccessible(true);
                    if (params.length == 0) {
                        m.invoke(messageRef);
                        return;
                    }
                    if (params.length == 1 && (params[0] == boolean.class || params[0] == Boolean.class)) {
                        m.invoke(messageRef, false);
                        return;
                    }
                } catch (Exception ignored) {}
            }
            c = c.getSuperclass();
        }
        Log.w(TAG, "dismiss() not found on " + messageRef.getClass().getName());
    }

    /**
     * JS から Unity に送るための名前（iOS の AEPInAppCallback と統一）。
     * HTML で webkit.messageHandlers.AEPInAppCallback.postMessage(action) の代わりに
     * Android では AEPInAppCallback.postMessage(action) で呼ぶ。
     */
    public static final String JAVASCRIPT_INTERFACE_NAME = "AEPInAppCallback";

    /**
     * addJavascriptInterface に渡すオブジェクト。postMessage(payload) で Unity に送り、track する。
     */
    public static Object createJsInterface(final Object messageRef, final String unityTarget) {
        return new Object() {
            @JavascriptInterface
            public void postMessage(String payload) {
                String p = (payload != null) ? payload : "";
                AEPSdkBridge.sendToUnityInAppCallback(unityTarget, p);
                trackOnly(messageRef, p.isEmpty() ? "click" : p);
            }
        };
    }

    public static void trackOnly(Object messageRef, String action) {
        if (messageRef == null) return;
        try {
            Class<?> edgeTypeClass = Class.forName("com.adobe.marketing.mobile.messaging.MessagingEdgeEventType");
            Object interactType = null;
            for (Object e : edgeTypeClass.getEnumConstants()) {
                if (e != null && ("INTERACT".equals(e.toString()) || "interact".equalsIgnoreCase(e.toString()))) {
                    interactType = e;
                    break;
                }
            }
            if (interactType == null) interactType = edgeTypeClass.getEnumConstants()[0];
            Method track = messageRef.getClass().getMethod("track", String.class, edgeTypeClass);
            track.invoke(messageRef, action != null ? action : "click", interactType);
        } catch (Exception e) {
            Log.e(TAG, "trackOnly failed", e);
        }
    }

    public static final class Result {
        public final boolean handled;
        public final String interaction;

        Result(boolean handled, String interaction) {
            this.handled = handled;
            this.interaction = interaction != null ? interaction : "webview";
        }
    }
}
