import Foundation
import GoogleCast
import Shared

/// The Google Cast sender SDK behind the shared cast code (docs/design/ios-casting.md, sections 2, 3 and 7).
///
/// `SwiftCastBridge` and `SwiftCastListener` are Kotlin interfaces from :spikeui (Objective-C names SharedSwiftCastBridge and
/// SharedSwiftCastListener, Swift names without the prefix). They carry only Int, Float, Bool and String, because this app sees
/// no other part of :shared. Connection state codes: 0 disconnected, 1 connecting, 2 connected, 3 failed.
///
/// Rules this class keeps:
/// - Every Cast SDK call and every callback to Kotlin runs on the main thread (Kotlin may call from any thread).
/// - It never starts discovery by itself: the local-network explanation comes first, and the shared code calls `startDiscovery()`.
/// - The SDK's selectors are spelled out with @objc(...) so a wrong Swift label cannot silently disable a delegate method.
/// - Volume goes through the session (`setDeviceVolume`, `setDeviceMuted`). Hardware volume buttons are not supported for Cast on iOS.
final class GoogleCastBridge: NSObject, SwiftCastBridge, GCKDiscoveryManagerListener, GCKSessionManagerListener {
    private var listener: SwiftCastListener?
    private var listening = false

    override init() {
        super.init()
    }

    // MARK: SwiftCastBridge (called from any thread)

    func setListener(listener: SwiftCastListener?) {
        onMain {
            self.listener = listener
            if listener != nil { self.attachSdkListeners() }
        }
    }

    func startDiscovery() {
        onMain {
            self.attachSdkListeners()
            let discovery = GCKCastContext.sharedInstance().discoveryManager
            discovery.startDiscovery()
            self.publishDevices()
        }
    }

    func stopDiscovery() {
        onMain {
            GCKCastContext.sharedInstance().discoveryManager.stopDiscovery()
        }
    }

    func connect(deviceId: String) {
        onMain {
            let context = GCKCastContext.sharedInstance()
            guard let device = context.discoveryManager.device(withUniqueID: deviceId) else {
                self.listener?.onError(message: "That device is no longer in range.")
                return
            }
            if !context.sessionManager.startSession(with: device) {
                self.listener?.onError(message: "Could not start a cast session.")
            }
        }
    }

    func disconnect() {
        onMain {
            _ = GCKCastContext.sharedInstance().sessionManager.endSessionAndStopCasting(true)
        }
    }

    func loadImage(url: String, title: String) {
        onMain {
            let sessions = GCKCastContext.sharedInstance().sessionManager
            guard let client = (sessions.currentCastSession ?? sessions.currentSession)?.remoteMediaClient else { return }
            guard let contentURL = URL(string: url) else {
                self.listener?.onError(message: "That photo has no usable address.")
                return
            }
            let metadata = GCKMediaMetadata(metadataType: .generic)
            metadata.setString(title, forKey: kGCKMetadataKeyTitle)
            let builder = GCKMediaInformationBuilder(contentURL: contentURL)
            builder.streamType = GCKMediaStreamType.none
            builder.contentType = "image/jpeg"
            builder.metadata = metadata
            client.loadMedia(builder.build())
        }
    }

    func setVolume(volume: Float) {
        onMain {
            let level = min(max(volume, 0), 1)
            GCKCastContext.sharedInstance().sessionManager.currentSession?.setDeviceVolume(level)
        }
    }

    func setMuted(muted: Bool) {
        onMain {
            GCKCastContext.sharedInstance().sessionManager.currentSession?.setDeviceMuted(muted)
        }
    }

    // MARK: Discovery

    @objc(didUpdateDeviceList)
    func didUpdateDeviceList() {
        publishDevices()
    }

    // MARK: Sessions

    @objc(sessionManager:willStartSession:)
    func sessionManager(_ sessionManager: GCKSessionManager, willStart session: GCKSession) {
        publishSession(session, state: 1)
    }

    @objc(sessionManager:didStartSession:)
    func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKSession) {
        publishSession(session, state: 2)
        listener?.onVolume(volume: session.currentDeviceVolume, muted: session.currentDeviceMuted)
    }

    @objc(sessionManager:didFailToStartSession:withError:)
    func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKSession, withError error: Error) {
        publishSession(nil, state: 0)
        listener?.onError(message: error.localizedDescription)
    }

    @objc(sessionManager:didEndSession:withError:)
    func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKSession, withError error: Error?) {
        publishSession(nil, state: 0)
        if let error = error { listener?.onError(message: error.localizedDescription) }
    }

    @objc(sessionManager:didSuspendSession:withReason:)
    func sessionManager(_ sessionManager: GCKSessionManager, didSuspend session: GCKSession, with reason: GCKConnectionSuspendReason) {
        publishSession(session, state: 1)
    }

    @objc(sessionManager:didResumeSession:)
    func sessionManager(_ sessionManager: GCKSessionManager, didResumeSession session: GCKSession) {
        publishSession(session, state: 2)
        listener?.onVolume(volume: session.currentDeviceVolume, muted: session.currentDeviceMuted)
    }

    @objc(sessionManager:session:didReceiveDeviceVolume:muted:)
    func sessionManager(_ sessionManager: GCKSessionManager, session: GCKSession, didReceiveDeviceVolume volume: Float, muted: Bool) {
        listener?.onVolume(volume: volume, muted: muted)
    }

    // MARK: Helpers

    /// Adds this object to the SDK's listener lists once. Adding a listener does not start discovery.
    private func attachSdkListeners() {
        guard !listening, GCKCastContext.isSharedInstanceInitialized() else { return }
        listening = true
        let context = GCKCastContext.sharedInstance()
        context.discoveryManager.add(self)
        context.sessionManager.add(self)
    }

    private func publishDevices() {
        guard let listener = listener, GCKCastContext.isSharedInstanceInitialized() else { return }
        let context = GCKCastContext.sharedInstance()
        let discovery = context.discoveryManager
        let current = context.sessionManager.currentSession
        var devices: [SwiftCastDevice] = []
        for index in 0..<discovery.deviceCount {
            let device = discovery.device(at: index)
            var state: Int32 = 0
            if let session = current, session.device.uniqueID == device.uniqueID {
                state = Self.stateCode(session.connectionState)
            }
            devices.append(Self.describe(device, state: state))
        }
        listener.onDevices(devices: devices)
    }

    private func publishSession(_ session: GCKSession?, state: Int32) {
        if let session = session {
            let device = session.device
            listener?.onSession(id: device.uniqueID, name: Self.name(of: device), ip: device.networkAddress.ipAddress ?? "", state: state)
        } else {
            listener?.onSession(id: nil, name: nil, ip: nil, state: 0)
        }
        publishDevices()
    }

    private static func describe(_ device: GCKDevice, state: Int32) -> SwiftCastDevice {
        SwiftCastDevice(id: device.uniqueID, name: name(of: device), ip: device.networkAddress.ipAddress ?? "", state: state)
    }

    private static func name(of device: GCKDevice) -> String {
        if let friendly = device.friendlyName, !friendly.isEmpty { return friendly }
        if let model = device.modelName, !model.isEmpty { return model }
        return device.uniqueID
    }

    private static func stateCode(_ state: GCKConnectionState) -> Int32 {
        switch state {
        case .connecting: return 1
        case .connected: return 2
        default: return 0
        }
    }

    private func onMain(_ work: @escaping () -> Void) {
        if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
    }
}
