#import <Foundation/Foundation.h>
#import "UnityFramework/UnityFramework-Swift.h" // 自動生成されるヘッダー

// Unity関数の宣言
extern void UnitySendMessage(const char* obj, const char* method, const char* msg);
extern UIViewController* UnityGetGLViewController();

extern "C" {
    // Swift側からUnityのViewControllerを取得するためのヘルパー関数
    UIViewController* _unity_get_view_controller() {
        return UnityGetGLViewController();
    }
    // 非同期初期化（appId は C# が StreamingAssets から読み取り渡す。コールバックでUnityに通知）
    void _ios_aep_initialize(const char* appId, const char* gameObjectName, const char* callbackMethodName) {
        NSString *appIdStr = appId ? [NSString stringWithUTF8String:appId] : @"";
        NSString *gameObjectNameStr = [NSString stringWithUTF8String:gameObjectName];
        NSString *callbackMethodNameStr = [NSString stringWithUTF8String:callbackMethodName];
        
        [AEPSdkBridge setupSDKWithAppId:appIdStr callback:^(BOOL success) {
            const char* result = success ? "success" : "failed";
            UnitySendMessage([gameObjectNameStr UTF8String],
                           [callbackMethodNameStr UTF8String],
                           result);
        }];
    }
    
    // Assuranceを手動で起動（デバッグ用）
    void _ios_aep_startAssurance() {
        [AEPSdkBridge startAssuranceSession];
    }
    
    void _ios_aep_sendEvent(const char* eventName, const char* jsonData) {
        NSString *eventNameStr = [NSString stringWithUTF8String:eventName];
        NSString *jsonDataStr = [NSString stringWithUTF8String:jsonData];
        [AEPSdkBridge sendEvent:eventNameStr jsonData:jsonDataStr];
    }
    
    void _ios_aep_updateIdentities(const char* identifierType, const char* identifier) {
        NSString *identifierTypeStr = [NSString stringWithUTF8String:identifierType];
        NSString *identifierStr = [NSString stringWithUTF8String:identifier];
        [AEPSdkBridge updateIdentities:identifierTypeStr identifier:identifierStr];
    }
    
    // 手動でPropositionを更新（完了通知付き）
    void _ios_aep_updatePropositionsManually(const char* surfacePath, const char* gameObjectName) {
        NSString *surfacePathStr = [NSString stringWithUTF8String:surfacePath];
        NSString *gameObjectNameStr = [NSString stringWithUTF8String:gameObjectName];
        [AEPSdkBridge updatePropositionsManuallyWithSurfacePath:surfacePathStr gameObjectName:gameObjectNameStr];
    }
    
    void _ios_aep_prefetchContentCards(const char* surfacePath, const char* gameObjectName) {
        NSString *surfacePathStr = surfacePath ? [NSString stringWithUTF8String:surfacePath] : @"square";
        NSString *gameObjectNameStr = gameObjectName ? [NSString stringWithUTF8String:gameObjectName] : @"AEPManager";
        [AEPSdkBridge prefetchContentCardsWithSurfacePath:surfacePathStr gameObjectName:gameObjectNameStr];
    }
    
    void _ios_aep_setInAppMessageCallbackTarget(const char* name) {
        NSString *nameStr = name ? [NSString stringWithUTF8String:name] : @"AEPManager";
        [AEPSdkBridge setInAppMessageCallbackTarget:nameStr];
    }
    
    void _ios_aep_getContentCardsForUnity(const char* surfacePath, const char* gameObjectName, const char* callbackMethodName) {
        NSString *surfacePathStr = [NSString stringWithUTF8String:surfacePath];
        NSString *gameObjectNameStr = [NSString stringWithUTF8String:gameObjectName];
        NSString *callbackMethodNameStr = [NSString stringWithUTF8String:callbackMethodName];
        
        [AEPSdkBridge getContentCardsForUnity:surfacePathStr 
                                     callback:^(NSString *jsonResult) {
            UnitySendMessage([gameObjectNameStr UTF8String], 
                           [callbackMethodNameStr UTF8String], 
                           [jsonResult UTF8String]);
        }];
    }
    
    // テンプレート付き SwiftUI ScrollView でコンテンツカードを表示（templateStyle: "large" or "small"）
    void _ios_aep_showContentCardsWithTemplates(const char* surfacePath, const char* templateStyle) {
        NSString *surfacePathStr = [NSString stringWithUTF8String:surfacePath];
        NSString *templateStyleStr = templateStyle ? [NSString stringWithUTF8String:templateStyle] : @"large";
        [AEPSdkBridge showContentCardsSwiftUIWithTemplates:surfacePathStr templateStyle:templateStyleStr];
    }
}
