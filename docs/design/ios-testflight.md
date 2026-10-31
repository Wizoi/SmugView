# iOS Stage 4: TestFlight from GitHub Actions

Workflow: `.github/workflows/ios-testflight.yml` (manual only). Reading key: VERIFIED means the text of the Apple page was read;
UNVERIFIED means it is from general knowledge or the page did not show it. Several developer.apple.com documentation pages
(xcodebuild, CFBundleVersion, Distributing your app...) returned only a title, so their content stayed UNVERIFIED.

## Owner checklist, in order

1. **Agreements.** The Account Holder signs the latest agreement in App Store Connect, Business section. Without it no app can be
   added. VERIFIED, https://developer.apple.com/help/app-store-connect/create-an-app-record/add-a-new-app
2. **Register the bundle ID** `com.smugview.app` (Account Holder or Admin): developer.apple.com/account/resources, Identifiers, the +
   button, App IDs, Continue, App ID type, Continue, enter a description, choose Explicit App ID, enter the bundle ID, tick the
   capabilities (none are known to be needed; background modes and local network need no capability tick, UNVERIFIED), Continue,
   Register. VERIFIED, https://developer.apple.com/help/account/identifiers/register-an-app-id
3. **Create the app record** (Account Holder, Admin or App Manager): App Store Connect, Apps, +, New App; choose iOS, enter name,
   primary language, the bundle ID, a SKU; User Access; Create. A build can be uploaded only after the record exists. VERIFIED,
   https://developer.apple.com/help/app-store-connect/create-an-app-record/add-a-new-app. The page does not say whether the bundle ID
   must be registered first (step 2 does it anyway).
4. **Create the API key.** App Store Connect, Users and Access, Integrations, App Store Connect API. The Account Holder first clicks
   Request Access once, agrees to the terms and submits; Apple reviews it case by case. Then Team Keys, Generate API Key (Account Holder
   or Admin), enter a name, choose the role under Access, Generate. The name and role cannot be edited later; revoke and make a new key.
   VERIFIED, https://developer.apple.com/help/app-store-connect/get-started/app-store-connect-api
   - **Role to choose: Admin.** Reason: cloud-managed distribution certificates are an Account Holder / Admin function (VERIFIED,
     https://developer.apple.com/help/account/certificates/cloud-managed-certificates, which also says extra permissions exist to let
     Admins and Developers sign with them but not which). Whether App Manager is enough for `-allowProvisioningUpdates` is UNVERIFIED.
   - **Download the .p8 immediately: it can be downloaded only once.** VERIFIED (same page). Keep it off the repo.
   - The Key ID is in the key's row and the Issuer ID is above the key table: UNVERIFIED (the page read did not cover them; it is how
     the portal is commonly described).
5. **Add the three GitHub secrets** (repo Settings, Secrets and variables, Actions; or on the `testflight` environment):
   `ASC_KEY_ID` (the Key ID), `ASC_ISSUER_ID` (the Issuer ID), `ASC_KEY_P8` (base64 of the .p8). Optional: `SMUGMUG_API_KEY`.
   Put the base64 straight on the clipboard so nothing prints (PowerShell):
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\AuthKey_XXXXXXXXXX.p8")) | Set-Clipboard
   ```
   Paste it into the secret field, then clear the clipboard (`Set-Clipboard -Value ' '`). With the GitHub CLI instead:
   `[Convert]::ToBase64String([IO.File]::ReadAllBytes("...p8")) | gh secret set ASC_KEY_P8 --repo Wizoi/SmugView`.
6. **Create the `testflight` environment**: repo Settings, Environments, New environment, name `testflight`, tick Required reviewers,
   add yourself, Save. (Required reviewers on a public repo on a free plan: UNVERIFIED; the design doc already flagged it. If it is
   missing, the manual dispatch is the only gate.)
7. **Run it**: Actions, "iOS TestFlight upload", Run workflow, optionally a marketing version (empty reads `MARKETING_VERSION` from
   `iosApp/project.yml`, which has none today, so it falls back to `1.0.0`). Approve the environment prompt. Expect 40 to 90 minutes
   (the release link alone took 22 minutes on the runner, from the Stage 0 notes in the existing workflow).
8. **Add testers** (internal, no review). Add the tester as an App Store Connect user first (Users and Access; roles that can test are
   listed on the page). Then App Store Connect, the app, TestFlight, + beside Internal Testing, name the group, Create (tick
   Enable automatic distribution to get new builds delivered automatically), select the group, Invite Testers, pick the users, Add.
   If automatic distribution is off: group, Add Builds, choose the build, Next, fill What to Test, Add. Up to 100 internal testers per
   group. VERIFIED, https://developer.apple.com/help/app-store-connect/test-a-beta-version/add-internal-testers
   External testers (up to 10,000, by email or public link) need the first build to go through Beta App Review; VERIFIED,
   https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview. That page does not state outright that
   internal testing skips review; the add-internal-testers page mentions no review.
9. **Install on the iPad**: the tester accepts the emailed invitation, installs the free TestFlight app, and opens the build there. A
   build can be tested for up to 90 days. VERIFIED (both pages above).

## What can go wrong

- **Build number reuse.** Each upload needs a CFBundleVersion greater than earlier uploads of the same version (UNVERIFIED from Apple's
  own page; the CFBundleVersion page returned only its title). The workflow uses `github.run_number` (+ `build_offset`). The run number
  counts per workflow file, so renaming or replacing the file restarts it from 1; raise `build_offset` then.
- **Missing agreement.** Apps cannot be added until the Account Holder signs the latest agreement (VERIFIED, step 1).
- **Xcode version.** Apple lists iOS apps built with Xcode 26 or later as supported (VERIFIED,
  https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds); the workflow selects Xcode 26.2.
- **Cloud signing.** Cloud-managed certificates are created automatically when a signing request arrives, 90 days before expiry for
  renewals, and Xcode cloud-signs when no local certificate is found (VERIFIED, cloud-managed-certificates page). Whether this works
  headless with only an API key on a clean runner is UNVERIFIED, since the xcodebuild documentation could not be read. A failure there
  means switching to the commented-out fastlane match fallback in the workflow. Apple states no quota; none was found.
- **Export compliance.** `ITSAppUsesNonExemptEncryption` = false in the Info.plist (set in `project.yml`) is meant to stop the
  per-build question; Apple's overview page says to set the key in the Info.plist but did not show the key, so the exact behavior is
  UNVERIFIED (https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance).
- **Processing delay.** Uploaded builds are processed by App Store Connect before testers see them. No duration is documented in the
  pages read; a build can sit "Processing" for a while (UNVERIFIED).
- **API key access.** Only the Account Holder can request API access; only Account Holder or Admin can create or revoke team keys
  (VERIFIED, api page). A leaked key must be revoked at once; revoked keys cannot be restored.
- **Team key scope.** Team keys apply to all apps; access cannot be limited per app (VERIFIED).
- **Upload tools.** Current Apple options are Xcode, altool, Transporter and the App Store Connect API (VERIFIED, upload-builds
  page). Its altool example shows `-u username [-p password]` only; whether altool takes an API key is not on that page. The page
  suggests Transporter's command line with JWTs for API-key uploads. The workflow avoids all of them: `xcodebuild -exportArchive`
  with `destination = upload` uploads directly (UNVERIFIED in Apple text read; it is the documented ExportOptions behavior from
  general knowledge, as are the `method` value `app-store-connect` for Xcode 26 and the `-authentication*` flags).
