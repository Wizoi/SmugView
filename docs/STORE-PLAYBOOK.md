# Store playbook: shipping an app to Google Play and the App Store

What this project learned about publishing, written so a new project can start from it. Every line comes
from a real run, a store message or a repo file named next to it. Where something was NOT recorded, it says so.
Written 2026-11-27 from SmugView 0.9.8 (Android) and 1.0.x (iOS).

## 0. What already exists in this repo

| Need | Where |
|---|---|
| Play listing text, assets, checklist, publish commands | [PUBLISH.md](PUBLISH.md) |
| Play upload as an agent skill | `.claude/skills/publish-app`, `.claude/skills/deploy-app` (Android only; they point at YAML guides) |
| iOS: owner checklist for the first TestFlight upload | [design/ios-testflight.md](design/ios-testflight.md) |
| iOS: App Store guidelines, privacy answers, the whole plan | [design/ios-cross-platform-and-app-store.md](design/ios-cross-platform-and-app-store.md), [design/ios-privacy-answers.md](design/ios-privacy-answers.md) |
| iOS: build, sign and upload in CI | `.github/workflows/ios-testflight.yml` (manual, waits for the `testflight` environment reviewer) |
| iOS: build and simulator checks, store screenshots | `ios-checks.yml`, `ios-spike.yml`, `ios-store-screenshots.yml`, `scripts/caption-screenshots.py` |
| Every store problem we hit, with evidence | [findings.md](findings.md) (search the run ids and ITMS codes below) |

There is no single "release" command. Android is one Gradle task; iOS is a GitHub workflow plus App Store Connect clicks.

## 1. Google Play

**Commands** (Gradle Play Publisher, service account in `play-service-account.json`, never committed):

```
./gradlew publishReleaseBundle                      # builds and uploads to internal; needs a NEW versionCode
./gradlew promoteArtifact --from-track internal --promote-track alpha
./gradlew promoteArtifact --from-track alpha --promote-track production --release-status inProgress --user-fraction 0.2   # staged
./gradlew promoteArtifact --from-track alpha --promote-track production --release-status completed                      # 100%
```

- A versionCode can never be reused, even after a rejected upload. Release notes are a file per versionCode:
  `app/src/main/play/release-notes/en-US/<versionCode>.txt`.
- Do not publish a `-PlocalBuild` bundle: it carries a `-local` version suffix. Use `-PlocalBuild` only for device tests.
- Use Windows Git Bash: `JAVA_HOME` must be the Android Studio JBR, and do not set `MSYS_NO_PATHCONV` in the same command.
- **Staged rollout is the safe default** for production (20, 50, 100). Promoting the same versionCode again with
  `--release-status completed` raises it to 100% (done 2026-11-27 for 0.9.8).
- New developer accounts need a closed test with enough testers for 14 continuous days before production (PUBLISH.md).
- **targetSdk deadline:** Play wants targetSdk within a year of the newest Android (API 36 since 2026-08-30).
- **Edge-to-edge (finding #28, Play pre-launch report):** at targetSdk 35+ Android 15 forces it, so call
  `enableEdgeToEdge()`, do not call `window.statusBarColor` / `navigationBarColor` (deprecated, ignored from Android 15, and Play
  flags them), and give every non-Scaffold screen `systemBarsPadding()`. Scaffold screens are already fine.
  A dark-only app: `SystemBarStyle.dark(Color.TRANSPARENT)`; with a non-transparent scrim colour the bar's colour came out as
  that colour in a Robolectric test (API 33), not transparent.
- Read the **pre-launch report** after each upload; it was where the edge-to-edge warnings first appeared.
- Keep the R8 mapping per release (`release-mappings/<versionCode>`); crash lines are unreadable without it.

## 2. Apple: App Store and TestFlight

**Flow:** push the code, run `ios-testflight.yml` (inputs: `marketing_version`, `build_offset`, `submit_beta_review`), approve the
environment, wait for "UPLOAD SUCCEEDED", wait for Apple's processing, then in App Store Connect pick the build on the version and
submit. The build number is the workflow's run number plus `build_offset`, so it only goes up.

- **Beta App Review is not App Review.** `submit_beta_review` only sends the build to Apple's review for external TestFlight testers.
  Submitting a version to the store is always a manual step in App Store Connect.
- **Swapping the build on a rejected version:** the submission must be removed from review first; then the Build section has a small
  minus button next to the old build (easy to miss), then "+" to add the new one. A rejected version has not shipped, so it
  keeps its version number; you do not need 1.0.2 to fix 1.0.1.
- **A rejection we got (2026-11-20, guideline 2.1(a)):** controls that were disabled until a site was chosen looked "unresponsive" to the
  reviewer, who never chose one. Lesson: a disabled control with no explanation reads as a bug. Keep it enabled and say what to do.
  Also give the reviewer a working example (a public nickname) in App Review Information, Notes.
- **Signing in CI:** automatic signing with an App Store Connect API key (`ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8` as base64).
  Archive **unsigned**, then export signed: archiving with signing made Xcode ask for a development profile and a registered
  device (run 41166250330).
- **`altool` can print UPLOAD FAILED and still exit 0.** The workflow fails on its words too (run 41167504875). The runner's Python
  is externally managed, so install tools in a venv (run 41243114773).
- **App Store Connect rejections seen on real uploads** (all fixed in `iosApp/project.yml`):
  - an embedded static framework fails the signature check: link it, set `embed: false` (run 41167504875);
  - an iPad app that allows multitasking must list all four orientations;
  - ITMS-90683: the Google Cast SDK touches CoreBluetooth, so Info.plist needs a purpose string;
  - set `ITSAppUsesNonExemptEncryption` to false in Info.plist when only OS HTTPS is used; it is meant to stop the per-build
    export-compliance question, but `design/ios-testflight.md` marks the exact effect UNVERIFIED, so watch the first build;
  - since 2024-05-01 required-reason APIs must be declared in `PrivacyInfo.xcprivacy`;
  - Compose Multiplatform needs `CADisableMinimumFrameDurationOnPhone: true` or it refuses to start on ProMotion iPhones.
- **Apple's SDK deadline (ITMS-90725):** from April 2027 uploads need the iOS 27 SDK (Xcode 27+). The warning appears on every
  upload before then. Seen on build 21 (Xcode 26.2), gone on build 22 (Xcode 27.1).

### Xcode versions on GitHub runners (checked 2026-11-27)

- `macos-15` has Xcode up to 26.3 (default 16.4); `macos-26` up to 26.6. **Xcode 27 is on its own image label, `xcode-27`**
  (27.0, 27.1 default, 27.2; macOS 27 base). Use `runs-on: xcode-27` and `sudo xcode-select -s /Applications/Xcode_27.1.app`.
  Source: actions/runner-images issue 14404. Re-check the list monthly; it changes.
- iPhone Duo (foldable) builds are the reason to be on 27.1 or later (runner-images comments).
- Kotlin/Native 2.3.21, Compose Multiplatform, XcodeGen and the Google Cast static library all built on Xcode 27.1 with no code change.
- A prebuilt binary built with an older SDK links on a newer Xcode. The reverse is what broke: the Cast static library references an
  iOS 26 symbol (`UIGlassEffect`), so it needed Xcode 26 or later.

## 3. Icons (what is recorded)

- Play needs a 512x512 hi-res icon and a 1024x500 feature graphic; real in-app screenshots beat abstract logo lockups
  (PUBLISH.md: the first five feature-graphic designs all looked like the same template).
- The iOS app icon (`AppIcon.png` in the asset catalog, `ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon`) was made from the Android
  launcher art (commit 07ce4f4).
- In-app UI icons: `material-icons-extended` is not published for Compose Multiplatform, so the used icons were copied into shared
  code as `ImageVector`s (`AppIcons`). Count the icons you use before choosing a UI library.
- Notifications and a dark app: with a dark-only design, set light status-bar icons explicitly (finding #25), in the light system
  theme too.
- **Not recorded in the repo:** any icon-specific store rejection or sizing lesson beyond the above. If there was one (alpha channel,
  masking, adaptive icon layers), add it here with its date.

## 4. Google APIs and SDKs

- **Google Cast sender SDK for iOS:** 4.8.6 is the newest tag (checked 2026-11-27, `git ls-remote`). Use the static product
  (`GoogleCastStatic`); the dynamic one double-defined GTMSessionFetcher (run 41141946510).
- **Cast to a Nest Hub (finding #29, closed without a root cause):** a Nest Hub showed its music-style now-playing card and no
  picture for a photo sent through the Default Media Receiver. The app never checked the result of `RemoteMediaClient.load`, so
  nothing said why. The app now reports a Cast code on the cast screen and logs it. Always observe the result of a cast load.
- **Play Developer API:** only through Gradle Play Publisher here; no direct calls.

## 5. Process lessons worth copying

- **Check the premise against real data before a fix** (live API call), and write the failing test first. Findings-first: every
  defect gets a row with evidence before it is fixed (`findings.md`).
- **Make sure CI actually ran the thing you rely on.** `ios-spike.yml` only runs its jobs on pushes to branch `spike/ios`, so pushes to
  other branches never ran the iOS native tests, and a manual run with `release_link` on skips them. Run it by hand with
  `release_link=false` to get the JVM tests, `iosSimulatorArm64Test` and the simulator screen. A scheduled run also failed once only
  because its release-link job was cancelled by the runner.
- **Room migrations:** one list (`Migrations.all`) used by Android, iOS and every test; a guard test checks it. Test the upgrade on a
  real install (old APK, cache a real site, `adb install -r` the new one, pull the database and compare). New markers are not
  backfilled when a re-fetch is cheap and a failure falls back to the cache.
- **Emulator:** `-gpu swiftshader_indirect -wipe-data` boots; wait for the load average to drop (it was 39 right after a wipe, and
  the app showed "isn't responding"); stop adb and start the emulator first, then the adb server, if it shows `offline`.
- **Release order that worked:** full test suite green, version bump and release notes, push, publish to internal, promote to alpha,
  staged production only with the owner's explicit yes. The owner decides production and App Store submission.
