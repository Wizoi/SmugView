# iOS casting: Google Cast and AirPlay external display (plan for owner sign-off)

Status: **approved by the owner on 2026-10-30** (the six decisions in section 8 are settled). Written 2026-10-30. Decisions already made by the owner: iOS v1 gets Google Cast
(parity with Android) and AirPlay done as an **external-display scene** (the photo viewer on the connected screen, controls on the phone).
Roku and Fire TV are on the backburner. This replaces `ios-cross-platform-and-app-store.md` 4.8's "no casting in v1".

Sources: a read-only research pass on 2026-10-30. The Cast SDK 4.8.6 static zip was downloaded and its headers and privacy manifest read
(primary source). Apple's external-display page and Google's docs were read. Everything not read there is marked UNVERIFIED.

## 1. What Android does today (the parity target)

`CastManager` (shared interface, 11 StateFlows, about 15 commands) implemented on Android by `DefaultCastManager`: Google Cast framework
with Google's **Default Media Receiver** (no custom receiver app id), plus Roku, Fire TV and a Web Companion server (all out of scope).
The screens and `CastController` talk only to the interface. iOS currently uses a no-op `NoCastManager` in `IosApp.kt`.

## 2. Architecture

- **Swift in `iosApp`, no cinterop.** The Cast SDK is Objective-C with blocks, delegates and categories; binding it through Kotlin/Native
  cinterop with no Mac means every `.def` or header error is found only in CI. Swift calls the SDK directly.
- **A slim `CastBridge` interface in `:shared` commonMain**, plain types and one callback sink, no Flows (a Kotlin Flow is awkward from
  Swift without SKIE, and SKIE's support for Kotlin 2.3.21 is UNVERIFIED):
  commands `startDiscovery`, `stopDiscovery`, `connect(deviceId)`, `disconnect`, `loadImage(url, title)`, `loadQueue(urls, intervalSec)`,
  `play`, `pause`, `next`, `previous`, `setVolume(Float)`, `setMuted(Boolean)`, `setListener(CastBridgeListener)`;
  listener `onDevices(List<CastDevice>)`, `onSession(CastDevice?)`, `onMedia(index, playing)`, `onVolume(volume, muted)`, `onError(message)`.
  Avoid overloads (they become selector names in Objective-C).
- **`IosCastManager : CastManager` in `:shared` iosMain** (or commonMain, so it is testable on Windows): owns the StateFlows and the
  slideshow timer and state machine, delegates SDK calls to the bridge. The existing `CastController` and screens do not change.
- **`GoogleCastBridge.swift` in `iosApp`** wraps `GCKDiscoveryManager`, `GCKSessionManager` and `GCKRemoteMediaClient`.
- **Wiring:** `IosAppViewController(castBridge:)`; the Swift app passes the instance in. The `Shared` framework already contains the
  interface; only the entry point needs to be callable from Swift (the framework export was narrowed in 6ca86ef, so the entry function and
  the bridge types must be visible: either re-export `:shared` or put a thin factory in `:spikeui`).
- **No bridge available (simulator, tests):** `IosCastManager` runs against a `FakeCastBridge`.
- **Slideshow model:** manual advance (a timer in `IosCastManager` loads one photo at a time), the same model as Android, instead of the
  SDK's queue (queue API names UNVERIFIED).
- **AirPlay external viewer:** a second Kotlin entry point `ExternalViewerViewController()` hosting a Compose `ExternalViewer` (black
  background, the current photo with a crossfade, no controls), created in the external scene's delegate. It reads the **same**
  `SmugViewModel` as the phone UI, so `IosRoot.viewModel()` must become a memoized singleton first (it builds a new one per call today).

## 3. Google Cast iOS SDK facts

| Fact | Status |
|---|---|
| Latest 4.8.6 (2026-07-27), minimum **iOS 16** (4.8.4 was iOS 15; Google's page contradicts itself); the last CocoaPods release | VERIFIED (release notes) |
| Our deployment target is already 16.0 | VERIFIED (`iosApp/project.yml`) |
| SwiftPM package `https://github.com/googlecast/google-cast-ios-sdk`, products `GoogleCastStatic` and `GoogleCastDynamic` (use one). Static needs `-ObjC -lc++` and manual resource bundles; **dynamic is easier** | VERIFIED |
| XcodeGen can declare it: top-level `packages:` plus `- package:` and `product:` on the target | VERIFIED (XcodeGen docs); SPM resolve on the CI runner UNVERIFIED |
| The xcframework has `ios-arm64` and `ios-arm64_x86_64-simulator` slices, so a simulator build links | VERIFIED (read from the zip) |
| Info.plist: `NSBonjourServices` = `_googlecast._tcp` and `_<APPID>._googlecast._tcp` (default receiver id believed to be CC1AD845, not read from the SDK), and `NSLocalNetworkUsageDescription` | VERIFIED (Google); the id UNVERIFIED |
| Init at launch: `GCKCastOptions(discoveryCriteria: GCKDiscoveryCriteria(applicationID: kGCKDefaultMediaReceiverApplicationID))`, `GCKCastContext.setSharedInstanceWith` | VERIFIED |
| Discovery, session and remote-media-client APIs exist as named in the report (device list via `GCKDiscoveryManager` listener; `startSession(with:)`; `loadMedia`; `setDeviceVolume`, `setDeviceMuted`) | VERIFIED (headers); the session-listener callback names UNVERIFIED |
| Showing a JPEG on the **Default Media Receiver** | UNVERIFIED; Android uses it, so very likely works; confirm on a device |
| Physical volume buttons do not control a Cast session on iOS; an in-app slider is needed | VERIFIED |
| Google requires a Cast button on screens with castable content, and a first-time introduction | VERIFIED; whether a custom (non-`GCKUICastButton`) picker is allowed is UNVERIFIED (custom pickers are supported since 4.6.0 but you must call `startDiscovery` yourself) |
| Swift 6 language mode against the SDK's headers | UNVERIFIED; keep `SWIFT_VERSION: "5.0"` (already set) |

## 4. AirPlay external-display scene facts

- Apple's current API: external content goes in a scene with role `UISceneSession.Role.windowExternalDisplayNonInteractive`
  (VERIFIED, https://developer.apple.com/documentation/uikit/presenting-content-on-a-connected-display). Connect and disconnect arrive as
  `UIScene.willConnectNotification` and `didDisconnectNotification`.
- **From iOS 27** the app must register a `UISceneAccessory` (`externalNonInteractive(sceneConfiguration:userInfo:)`, then
  `registerSceneAccessory(_:)` on a view controller) before it gets that scene; before that, the system connects the scene itself. This
  confirms what the earlier design doc said. We build with Xcode 26 now, so the automatic path is enough until we build against the iOS 27
  SDK.
- The user connects "using AirPlay or a physical cable" (Apple). With nothing provided, the system **mirrors**; a scene with content
  replaces the mirror (strongly implied, not stated).
- **The biggest unknown (UNVERIFIED): whether an Apple TV or AirPlay TV reached by Control Center "Screen Mirroring" delivers the external
  scene to a third-party app.** `AVRoutePickerView` is for audio and video routes and may not trigger it. This can only be settled on the
  tester's iPad with an Apple TV or an AirPlay TV.
- Compose Multiplatform 1.12.2 with two `ComposeUIViewController` roots at once: UNVERIFIED.
- The scene is non-interactive: the phone keeps the controls (as decided).

## 5. Testing without a Mac or a device

- The Cast SDK finds nothing in the simulator or CI, and the simulator does not enforce local-network privacy (TN3179).
- The Simulator app has an External Displays menu, but a command-line control and a CI capture of a second display are UNVERIFIED. Do not
  plan on them.
- **Strategy:** (1) unit tests for `IosCastManager` against `FakeCastBridge` (slideshow timer, volume, reconnect), runnable in
  `:shared:jvmTest` on Windows; (2) a debug "fake cast target" device that routes the viewer into an in-app second window, so the logic and a
  screenshot work on the simulator; (3) a CI step proving `GoogleCastBridge.swift` compiles and links against the SDK; (4) **iPad/iPhone
  only, by hand:** discovery, the local-network prompt, loading an image on a real Chromecast, slideshow, volume, disconnect, and the AirPlay
  scene appearing on an Apple TV.

## 6. Risks

- **Local-network prompt:** appears at first discovery. Show our own explanation first; write a specific `NSLocalNetworkUsageDescription`;
  explain it in App Review notes.
- **Privacy manifest (VERIFIED, read from the SDK):** the Cast SDK bundles one declaring collected data types DeviceID,
  OtherDiagnosticData and ProductInteraction (purposes Analytics and AppFunctionality) and required-reason APIs (file timestamps,
  UserDefaults). Our App Store privacy answers and our own manifest must stay consistent with it. Since 4.8.4 the SDK also adds a Cast terms
  and privacy UI. **The owner should read this**, as SmugView otherwise collects no analytics.
- **App size:** the SDK download is 8.9 MB (dynamic) or 44.8 MB (static, binary about 46 MB); the real effect after thinning is UNVERIFIED.
- **Review blockers (UNVERIFIED):** missing Bonjour keys or usage text breaks discovery or draws review questions.
- The release link is already 22 minutes and 162 MB on CI; the SDK adds to the app, not to the Kotlin link.

## 7. Order of work (sizes are estimates, not sourced)

1. S: `CastBridge`, `CastBridgeListener`, `IosCastManager`, `FakeCastBridge`, unit tests (runs on Windows).
2. S/M: XcodeGen `packages:`, Info.plist keys, a CI simulator build with `GoogleCastDynamic`; settle the framework entry point so Swift sees the bridge.
3. M: `GoogleCastBridge.swift` (discovery, session, image, volume, disconnect), passed into `IosAppViewController`. **A TestFlight build here is already useful** (Cast works).
4. S: memoize the `SmugViewModel` singleton; the fake cast target debug switch.
5. M: external-display scene and `ExternalViewer`.
6. S/M: the by-hand pass on the iPad and a Chromecast and an Apple TV.

## 8. Decisions (all six settled by the owner, 2026-10-30)

1. **Cast button: our own device-selector sheet** (the one Android has), not Google's `GCKUICastButton`. We call `startDiscovery` ourselves. Open: confirm Google's branding rules for a custom picker (use Google's Cast icon template) before the store build.
2. **SDK flavour: dynamic** (`GoogleCastDynamic` through SwiftPM, declared in XcodeGen `packages:`).
3. **Data collection: ship Cast and declare it.** The App Store privacy answers and our own privacy manifest must list what the Cast SDK's manifest declares (DeviceID, OtherDiagnosticData, ProductInteraction for Analytics and AppFunctionality; required-reason APIs for file timestamps and UserDefaults). The privacy label therefore stops being 'no data collected'.
4. **Local-network permission: explain first, then scan.** A one-time screen on the first tap of the cast button, then the system prompt. Proposed wording: 'SmugView looks for Chromecasts and other cast devices on your Wi-Fi so you can show photos on your TV.' The same sentence is the `NSLocalNetworkUsageDescription`.
5. **iOS 27: current SDK, automatic scene.** Add the `UISceneAccessory` registration when we build against the iOS 27 SDK.
6. **AirPlay scene risk accepted.** Build the external viewer and test it on the iPad with an Apple TV; if the scene never arrives, plain screen mirroring is the fallback.
