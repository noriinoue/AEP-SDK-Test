package com.adobe.aep.unity;

import android.app.Application;
import android.util.Log;

import com.adobe.marketing.mobile.AdobeCallback;
import com.adobe.marketing.mobile.LoggingMode;
import com.adobe.marketing.mobile.MobileCore;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * カスタム Application。Application.onCreate() で AEP SDK を初期化し、
 * SDK が ActivityLifecycleCallbacks を「いかなる Activity の onResume より前」に登録できるようにする。
 * これにより、Unity のメイン Activity が resume したときに SDK が「現在の Activity」を正しく取得し、
 * In-App Message の「Current activity is null」を防ぐ。
 *
 * 有効にするには AndroidManifest の &lt;application&gt; に android:name="com.adobe.aep.unity.AEPApplication" を指定する。
 * App ID は StreamingAssets/AEPAppId.txt を APK の assets から読み取る（Unity がビルド時に同梱）。
 */
public class AEPApplication extends Application {
    private static final String TAG = "AEPApplication";
    private static final String[] ASSET_PATHS = new String[]{
            "bin/Data/AEPAppId.txt",
            "AEPAppId.txt",
            "StreamingAssets/AEPAppId.txt"
    };

    @Override
    public void onCreate() {
        super.onCreate();
        String appId = readAppIdFromAssets();
        if (appId == null || appId.trim().isEmpty()) {
            Log.w(TAG, "AEPAppId.txt not found or empty (tried: bin/Data/AEPAppId.txt, AEPAppId.txt, StreamingAssets/AEPAppId.txt). SDK will be initialized by Unity later. In-App Message may not display (Current activity is null).");
            return;
        }
        Log.d(TAG, "AEPAppId read from assets, initializing SDK in Application.onCreate for In-App Message.");
        try {
            MobileCore.setLogLevel(LoggingMode.DEBUG);
            MobileCore.initialize(this, appId.trim(), new AdobeCallback<Object>() {
                @Override
                public void call(Object o) {
                    AEPSdkBridge.onSdkInitializedByApplication(AEPApplication.this);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "AEP SDK initialization in Application.onCreate failed", e);
        }
    }

    /**
     * APK の assets から AEP App ID を読み取る。Unity の StreamingAssets はビルド時に assets に含まれる。
     */
    private String readAppIdFromAssets() {
        for (String path : ASSET_PATHS) {
            try (InputStream is = getAssets().open(path);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        sb.append(t);
                        break;
                    }
                }
                if (sb.length() > 0) {
                    Log.d(TAG, "Read appId from assets path: " + path);
                    return sb.toString();
                }
            } catch (Exception e) {
                // 次のパスを試す
            }
        }
        return null;
    }
}
