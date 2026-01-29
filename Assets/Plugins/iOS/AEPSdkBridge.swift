import Foundation
import AEPCore
import AEPAssurance
import AEPEdge
import AEPEdgeIdentity
import AEPMessaging

@objc public class AEPSdkBridge: NSObject {
    @objc public static func setupSDK() {
        MobileCore.setLogLevel(.debug)
        MobileCore.initialize(appId:"6a203c8a0ff8/0d1cfe126ee2/launch-8e810d80e4b7-development")
        Assurance.startSession()
    }

    @objc public static func setupSurface() {
        let _ = Surface(path: "home#square")
    }

    @objc public static func trackAction(_ action: String, data: [String: String]) {
        MobileCore.track(action: action, data: data)
    }
    
    @objc public static func sendEvent(_ eventName: String, data: [String: Any]) {
        var xdmData: [String: Any] = [:]
        xdmData["eventType"] = eventName
        xdmData.merge(data, uniquingKeysWith: { (current, _) in current })
        let experienceEvent = ExperienceEvent(xdm: xdmData)
        Edge.sendEvent(experienceEvent: experienceEvent)
    }
    
    @objc public static func syncIdentifier(_ identifierType: String, identifier: String) {
        // Identity.syncIdentifier(identifierType: identifierType, 
        //                        identifier: identifier, 
        //                        authentication: .unknown)
    }
    
    @objc public static func updateIdentities(_ identifierType: String, identifier: String) {
        let identityMap = IdentityMap()
        identityMap.add(item: IdentityItem(id: identifier), withNamespace: identifierType)
        Identity.updateIdentities(with: identityMap)
    }

    @objc public static func updatePropositionsForSurfaces(_ surfacePath: String) {
        let surface = Surface(path: surfacePath)
        Messaging.updatePropositionsForSurfaces([surface])
    }

}
