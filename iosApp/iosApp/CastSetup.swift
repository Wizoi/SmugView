import GoogleCast

/// Starts the Google Cast sender SDK. Google's integration guide says this must run early, at launch.
///
/// It uses Google's Default Media Receiver, the same one the Android app casts to, so no receiver has to be registered. Discovery is not
/// started here: the shared cast code starts it (through the bridge) when the user first opens the cast sheet, after the one-time
/// explanation of the local-network prompt (decision 4 of docs/design/ios-casting.md).
enum CastSetup {
    static func initialize() {
        let criteria = GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID)
        let options = GCKCastOptions(discoveryCriteria: criteria)
        GCKCastContext.setSharedInstanceWith(options)
    }
}
