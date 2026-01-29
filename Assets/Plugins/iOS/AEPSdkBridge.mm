#import <Foundation/Foundation.h>
#import "UnityFramework/UnityFramework-Swift.h" // 自動生成されるヘッダー

extern "C" {
    void _ios_aep_initialize() {
        [AEPSdkBridge setupSDK];
        [AEPSdkBridge setupSurface];
    }

    void _ios_aep_trackAction(const char* action, const char* dataKey, const char* dataValue) {
        NSString *actionName = [NSString stringWithUTF8String:action];
        NSDictionary *data = @{
            [NSString stringWithUTF8String:dataKey]: [NSString stringWithUTF8String:dataValue]
        };
        [AEPSdkBridge trackAction:actionName data:data];
    }
    
    void _ios_aep_sendEvent(const char* eventName, const char* data) {
        NSString *eventNameStr = [NSString stringWithUTF8String:eventName];
        NSDictionary *dataDict = [NSDictionary dictionaryWithObjectsAndKeys:
            [NSString stringWithUTF8String:data], [NSString stringWithUTF8String:data],
            nil];
        [AEPSdkBridge sendEvent:eventNameStr data:dataDict];
    }
    
    void _ios_aep_syncIdentifier(const char* identifierType, const char* identifier) {
        NSString *identifierTypeStr = [NSString stringWithUTF8String:identifierType];
        NSString *identifierStr = [NSString stringWithUTF8String:identifier];
        [AEPSdkBridge syncIdentifier:identifierTypeStr identifier:identifierStr];
    }
    
    void _ios_aep_updateIdentities(const char* identifierType, const char* identifier) {
        NSString *identifierTypeStr = [NSString stringWithUTF8String:identifierType];
        NSString *identifierStr = [NSString stringWithUTF8String:identifier];
        [AEPSdkBridge updateIdentities:identifierTypeStr identifier:identifierStr];
    }

    void _ios_aep_updatePropositionsForSurfaces(const char* surfacePath) {
        NSString *surfacePathStr = [NSString stringWithUTF8String:surfacePath];
        [AEPSdkBridge updatePropositionsForSurfaces:surfacePathStr];
    }
}
