# Retro v8: the Apple port, first working session (2026-10-29)

Scope: iOS design, Stage 0 (spike) and Stage 1a to 1e plus logging (see `docs/design/ios-cross-platform-and-app-store.md`, "Stage 1 status").
Facts here come from the session; each is tied to a findings row or a commit. Items marked UNVERIFIED are from memory and must be checked before they drive a decision.

## What worked, and should be repeated

1. **Spike first, with a measurable gate.** Stage 0 answered "can the shared code compile, pass its tests and render on iOS" in about a day, before any refactor. It also produced the numbers (device IPA 10.3 MB, 12 tests on the simulator).
2. **Generate from the source of truth instead of retyping.** The Ktor client (22 endpoints) was generated from the old Retrofit interface (22/22 matched); the six migrations were a mechanical transform; `ApiContractTest` and the loopback tests then ran unchanged against the new client.
3. **Compare identities before moving data code.** Every Room identity hash for versions 13 to 18 was compared between the app and the shared copy before the database moved; an install-over on an emulator then kept the user's collection.
4. **Keep the user-visible stack and change what sits on top.** Instead of rebuilding retry, cache and cookies as Ktor plugins, a small engine puts Ktor in front of the app's own `Call.Factory`. No cache reset, no re-tested retry logic.
5. **Real data before and after.** The 23 recorded answers found three fields Gson had silently let through (`Name`, `Node` in a user search, `Name` of a locked album).
6. **Write the approval scope down.** The owner's "assume the Apple port is approved if functionally the same" is a memory note, so it does not have to be asked again.

## What cost time, and the lesson in each

| What happened | Cost | Lesson |
|---|---|---|
| No Mac: every iOS check is a GitHub macOS run (6 to 45 minutes with the queue) | most of the wall-clock time | Treat the iOS job as the compiler for common code. Batch changes; after moving files or adding a dependency, read the iOS job before the next move. |
| `KeyedResultCache` compiled on the JVM and Android but used `LinkedHashMap(16, 0.75f, true)` and `@Synchronized` (JVM only) | one CI round | "Compiles on Android" proves nothing for iOS. The design's "51 import-clean files" was really 5: a file can be import-clean and still reference platform-bound classes in its own package. |
| Nine CI rounds for the Stage 0 toolchain. The error said "Could not find ...room-common-iosSimulatorArm64Main-2.7.1.klib"; the file was on disk and the real cause was that Room 2.7.1's iOS libraries are built with Kotlin 2.1.10 | about a third of Stage 0 | Read the klib manifest (`compiler_version`) and the compat edges before guessing at downloads or caches. Change one of Kotlin, KSP, Room, Hilt, AGP, Compose per round. |
| Compose Multiplatform 1.12 pulls androidx.compose that needs compileSdk 37 (AGP 9) | `:spikeui` split; Stage 2 blocked on AGP 9 | Check the Android-side requirements of a multiplatform library before the app depends on it. |
| Hidden classpath coupling twice: `androidx.hilt`'s processor used the Kotlin-metadata reader that Room's kapt processor supplied; `sqlite-bundled` raised `androidx.sqlite` under Room 2.7.1 and made Robolectric tests flaky | two debugging detours | After removing or adding a dependency, diff the resolved classpath (`:app:dependencies`) and the kapt/KSP classpath, not just the build result. |
| Two files named `ApiJson.kt` in one package in two modules produced the same JVM class | one detour | Same package plus same file name across modules collides. Name files by content. |
| Build logs need a login; the anonymous API allows 60 requests an hour; several monitors exhausted it | lost polling for a while | Annotations are the log channel (`annotate-log.sh`); poll with ETag/304 (free); one monitor at a time. |
| Both emulators stayed `offline` for over 10 minutes | about 26 minutes | If adb is still offline after 2 minutes, change the GPU mode (`-gpu swiftshader_indirect -wipe-data` booted in 75 s) instead of waiting. See `reference_emulator-boot`. |
| I printed "957 / 0" from stale result files after a failed build, and committed a broken move locally (not pushed) | a bad commit that was caught before pushing | Delete `build/test-results` first, and require `BUILD SUCCESSFUL`. This is already a CLAUDE.md rule; I broke it twice. |
| Flaky tests appeared after the change in threading (Ktor): 6 distinct ones | repeated full runs | A flake that appears after a change is a finding in the same step, classified as test or product. One was a real product race (`KeyedResultCache`), one a test iterating a list without its lock; `ViewedMarkScenarioTest` may be a product race and is still open. Also stop the emulator before a full suite. |
| The design's FTE-week estimates did not fit a team of two | wrong expectations | Estimate in working sessions and name the waiting (CI, soak, Apple). |

## Apple-specific things we have not met yet, and should learn before Stage 4

These are the places where Apple differs from Google Play. None has been tried; treat the claims as UNVERIFIED until checked.

1. **Upload SDK requirement.** I recall that from spring 2026 Apple requires uploads built with Xcode 26 and the iOS 26 SDK. The CI uses `macos-15` with Xcode 16.4 (iPhoneOS 18.5 SDK), which would be too old to upload. Before Stage 4: pin an Xcode 26 runner, check that Kotlin 2.3.21 and Compose Multiplatform 1.12 build with it, and re-measure the framework link time.
2. **Developer Program enrollment** is owner-side and can take days; nothing from Stage 4 on works without it. Start it now.
3. **Signing.** Certificates and provisioning profiles, kept by fastlane `match` in a private repo, are the equivalent of the upload key; the first dry run decides how much of this is automatable. The Play lesson (a rejected upload can burn a versionCode) has an Apple twin: a rejected build burns a build number.
4. **App Review rules 4.2 and 5.2.2** (minimum functionality; third-party service branding and permission) are the likeliest rejection for a SmugMug client. The owner decided not to write to SmugMug; the risk stays and the review notes should say what the app is.
5. **Privacy:** `PrivacyInfo.xcprivacy` reasons, Info.plist purpose strings (Photos add-only), App Privacy answers, and the existing privacy policy URL. A missing reason is a rejection that costs a build.
6. **Background work is not WorkManager.** `BGAppRefreshTask` gives about 30 seconds at the system's discretion; the new-gallery check must rotate through sites and also run on open. Keychain items need `AfterFirstUnlock` to be readable in the background.
7. **TestFlight:** internal testers (up to 100, no review) are enough for the first tester; external groups need a beta review.
8. **A real device.** Everything so far ran on a simulator or the JVM. Touch, GPU, the real photo picker/saver and background behaviour need an iPhone; the owner's tester is the way to get one (decision 7.5). The first tester build has the whole v1 feature set (owner decision).

## Proposed standing rules (the owner decides what goes into CLAUDE.md)

- After moving code to `commonMain` or adding a multiplatform dependency, **read the iOS job before the next move**. "Import-clean" is not "movable".
- **One toolchain component per CI round**; before bumping, check the klib `compiler_version` of dependencies and the `compileSdk` a multiplatform library needs.
- **Clear stale results and require `BUILD SUCCESSFUL`** before reading test counts; stop the emulator before a full suite.
- **A flake after a change is a finding the same step**, classed as test or product, with how often it fails alone and in a full run.
- **Apple lead times go on the owner's list early** (enrollment, a tester and device, the Xcode 26 question), not at Stage 4.
- **Keep the old stack and change what is on top** when the old stack is already tested (the engine over `Call.Factory` is the pattern).
- **Emulator that stays offline for 2 minutes: change the GPU mode** (swiftshader, wipe data).

## Still open from this session

- `ViewedMarkScenarioTest` race: product or test (findings, "Test flakes seen while moving the database").
- Phone-only checks and everything for Stage 3 (batched last, as before).
- The Xcode 26 requirement above: verify first, it can change the CI.
