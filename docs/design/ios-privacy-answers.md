# iOS privacy answers (App Store Connect, PrivacyInfo.xcprivacy)

Status: draft for the owner, 2026-10-30. VERIFIED = read in the Cast SDK file or in our code in this repo.
UNVERIFIED = from memory or not readable. Apple's page "Describing use of required reason API"
(developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api) is rendered by
JavaScript and WebFetch returned only its title, so every Apple reason-code description below is UNVERIFIED
until someone reads that page (or Xcode's privacy report on an archive confirms it).

## 1. The Google Cast SDK 4.8.6 manifest (VERIFIED, read from the downloaded dynamic and static xcframeworks)

All four `PrivacyInfo.xcprivacy` files (dynamic/static, device/simulator) have the same content:

- Collected: DeviceID, OtherDiagnosticData, ProductInteraction. Each: Linked = false, Tracking = false,
  purposes Analytics and AppFunctionality. (The static device/simulator files list the same three types.)
- Required-reason APIs: FileTimestamp C617.1; UserDefaults C56D.1 and CA92.1.
- No `NSPrivacyTracking` key and no tracking domains in the SDK manifest.

## 2. Required-reason APIs in our own code

| Category | Our use | Code | Notes |
|---|---|---|---|
| UserDefaults | VERIFIED: `NSUserDefaults(suiteName = name)` in `shared/src/iosMain/kotlin/com/smugview/app/IosApp.kt` (~line 179, `UserDefaultsStore`; suites smugview_prefs, smugview_search_status, smugview_offline_settings, smugview_sync_state) | CA92.1 | App-only data. Suite names are not app groups, so 1C8F.1 does not apply. Code meaning UNVERIFIED (memory). |
| DiskSpace | VERIFIED: `attributesOfFileSystemForPath(dir)[NSFileSystemFreeSize]` in `shared/src/iosMain/.../data/offline/PlatformFileOps.ios.kt` (`usableSpace`), used by `OfflineStore` as the free-space check before saving | E174.1 | "Check available disk space to write or delete files" (UNVERIFIED). Add 85F4.1 only if free space is shown to the user (not checked in UI code). |
| FileTimestamp | VERIFIED: `attributesOfItemAtPath` in the same file reads only `NSFileSize`. Declared defensively: SQLite, Ktor, Coil's disk cache and the Kotlin/Native runtime may read file times | C617.1 | Inside the app container only (UNVERIFIED meaning). Over-declaring is harmless; under-declaring risks an upload warning. |
| SystemBootTime | VERIFIED by grep: no direct use (only `NSDate().timeIntervalSince1970` in `Platform.ios.kt`, `NSProcessInfo.environment` for debug flags). Kotlin/Native monotonic time and networking libraries may use `mach_absolute_time` / uptime | 35F9.1 | Defensive, UNVERIFIED whether any dependency calls it. Trim if the Xcode privacy report shows none. |

Not required-reason, but needed on the Mac side (Info.plist, not this manifest), all UNVERIFIED from memory of Google's setup guide:
`NSLocalNetworkUsageDescription`, `NSBonjourServices` (`_googlecast._tcp`, `_<appId>._googlecast._tcp`),
`NSPhotoLibraryAddUsageDescription`, `BGTaskSchedulerPermittedIdentifiers`.

Best check: Xcode > Product > Archive > Generate Privacy Report, which merges our manifest and every framework's. Not done (no Mac here).

## 3. App Store Connect questionnaire (with the Cast SDK)

The label changes from "Data Not Collected" to "Data Collected". Declare (the types are VERIFIED in Google's manifest):

| Data type | Collected | Linked to user | Tracking | Purposes |
|---|---|---|---|---|
| Identifiers > Device ID | Yes | No | No | Analytics, App Functionality |
| Diagnostics > Other Diagnostic Data | Yes | No | No | Analytics, App Functionality |
| Usage Data > Product Interaction | Yes | No | No | Analytics, App Functionality |

Reasoning: SmugView has no server and no analytics SDK of its own; the entries come from Google's manifest. "Not linked"
and "no tracking" are Google's claims; what the SDK actually sends is UNVERIFIED. That an embedded SDK's collection counts as the app's
collection is Apple's rule as I remember it (UNVERIFIED).

Data going to SmugMug rather than to us: the user's nickname/site, album and photo requests, the API key. Whether to declare it
(Name/User ID, Photos or Videos) is the owner's call (section 5). Proposed default: not declared, because we never receive it
(UNVERIFIED reading of Apple's definition of "collect").

Unlock passwords: stored in the Keychain on the device (planned: IosApp.kt still says session-only until Stage 3, VERIFIED), sent only to SmugMug's unlock endpoint.

## 4. Privacy-policy points to check

1. The app sends the user's SmugMug nickname and all browsing requests to api.smugmug.com; SmugMug's policy applies. Say so.
2. The Cast SDK sends Device ID, diagnostic and interaction data to Google for analytics and app functionality (VERIFIED in manifest). Name Google, link its policy.
3. Local network: Cast discovery uses the local network; photos or links go only to the device the user picks.
4. On device: unlock passwords (Keychain), settings, saved photos, cache. Saving to Photos only on request.
5. Shared photos have camera EXIF removed; a shared link is the SmugMug web page.
6. Notifications are local only. No account with us, no ads, no tracking, no sale of data.

## 5. Decisions only the owner can make

- [OWNER] Accept the label change from "Data Not Collected" to the three Cast data types (ios-casting.md decision 3 already commits to declaring).
- [OWNER] Whether to also declare the data sent to SmugMug on the label, or treat it as the user's own service.
- [OWNER] The privacy policy text, URL and hosting, updated before submission.
- [OWNER] Keep the defensive FileTimestamp and SystemBootTime entries, or trim after the privacy report.
- [OWNER] Whether free disk space is ever shown to users (would add 85F4.1).
- [OWNER] Whether Cast analytics can be limited (no documented switch verified, UNVERIFIED); if so the label could shrink.
