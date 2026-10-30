# Handoff: iOS port, Stage 2 (written 2026-10-30)

Read first: CLAUDE.md, `docs/design/ios-cross-platform-and-app-store.md` ("Stage 1 is done; Stage 2 inventory"), `docs/findings.md` (last rows).

## State
- Branch `spike/ios`, level with `main` at `65865e0`. Working tree clean (a failed BackHandler import swap was reverted).
- Last verified: app suite 964/0, shared 21/0, `assembleRelease` OK, iOS CI green for the merged commits.
- Owner approvals: apple port work approved if functionally the same; push `spike/ios` freely; push `main` for verified work (full suite + release build + green iOS CI); releases/Play/promotion need a fresh yes. First tester build = whole v1 feature set (no partial build). Tester has an iPad (A16); test builds are universal.
- Owner-side gates: Apple Developer enrollment verification; app record / bundle ID (`com.smugview.app` proposed); App Store Connect API key (owner stores it; never ask for contents). Team ID `R28G4S7F77`.

## Next step (Stage 2: make the 29 UI files movable to `:shared`)
1. **BackHandler**: `androidx.compose.ui.backhandler` is not on the app's Jetpack Compose classpath (that is why the sed swap failed). Plan: `expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)` in `:shared` commonMain; Android actual calls `androidx.activity.compose.BackHandler` (add `androidx.activity:activity-compose:1.13.0` to `shared` `androidMain.dependencies`); jvm and ios actuals are no-ops (or iOS uses the CMP one). Then switch the 6 browser tab files' imports.
2. `PlatformActions` interface: share text, share photo file (FileProvider link), notification permission request/check (`FollowSiteAction`), photo-save permission (`PhotoDetailScreen`), clipboard "Link copied".
3. ZXing QR to a multiplatform QR; Palette to a common colour sampler or drop; `expect VideoPlayer` (ExoPlayer / AVPlayer); `Uri/Bitmap/HttpURLConnection` helpers.
4. Move UI files to `:shared` one by one (check with `:shared:compileCommonMainKotlinMetadata` + iOS CI), navigation graph (`MainActivity`) to JetBrains navigation-compose, iOS `MainViewController` host with a hand-written composition root.
5. Then Stage 3 (Keychain PasswordStore, Photos saver, share sheet, BGAppRefreshTask, local notifications, privacy manifest), Stage 4 (signing/TestFlight after Apple verification), Stage 5 (submission).

## Rules that bit us
- Clear `app/build/test-results shared/build/test-results` before test runs; require `BUILD SUCCESSFUL`.
- Pre-commit: `./gradlew.bat :shared:jvmTest testDebugUnitTest` with the Android Studio JBR.
- Do not push many commits in quick succession (macOS CI queue); poll the latest run (`$TEMP/ci_poll.sh`, `$TEMP/run_poll.sh <run-id>`) before merging.
- Write files with the Write tool (bash heredocs mangle backticks/backslashes).
- Never echo the SmugMug API key or the Family password.
