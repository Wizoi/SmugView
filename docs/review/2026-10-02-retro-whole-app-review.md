# Retro: whole-app review, phases 0 to 6 (closed 2026-10-02)

Written for the owner. Facts come from `docs/findings.md`, the git log and this session; anything I did not
verify says so. Proposed rule changes are listed at the end and are **not** applied: they need your yes.

## 1. Where it ended up

- Phases 0 to 6 done (6-19 skipped by your Q13 (a)). v0.8.1 (versionCode 27) is on the Play **internal** track and
  `main` is pushed (`4b39b22`). Not promoted past internal.
- Unit tests grew from 92 to 899 (4 skipped, 0 failing, green twice on the final code). The suite now takes
  about 134 s instead of 249 s (section 3).
- Bugs the phone found after 899 green tests: the dark status bar was too narrow (#25), the breadcrumb showed the
  previous gallery's parent (#26), and the pinch-zoom limit was wrong for portrait photos and capped at 10x. All three
  are fixed and committed; the zoom was confirmed by you on the phone, the other two were seen fixed or covered by a test
  that failed first.

## 2. What went wrong, in the order it cost us

| # | What happened | Why it happened | What to do |
|---|---|---|---|
| 1 | An implementing agent spent about 2 hours on a flaky test without committing; you had to kill it. | No timebox, and nobody checked in. I let it run in the background and only looked when you asked. | Every agent gets a wall-clock box (45 min) and must commit or report by then. I check on any agent past its box instead of waiting for the notification. |
| 2 | Agents reported green while my own runs disagreed. | They ran a subset or their own build; two builds in one tree also collide (R-69). | I run the full suite myself before any commit; an agent's "green" is a claim, not a result. |
| 3 | Parallel agents kept touching the same hot files (`ScreenRig`, `findings.md`, `SmugViewModel`). | No ownership map. | Name one owner per shared file in each plan; shared files are edited by me only. |
| 4 | Flaky screen tests: `AndroidUiDispatcher.Main` wedged after a looper reset, and assert-after-await races (`ViewedMarkScenarioTest`). | Real timing under a paused main looper. The dispatcher cause was found by dumping it, after the hunt in item 1. | `ScreenRig.healUiDispatcher()` stays. A new wait helper is written to settle first and assert second, never the other way round. |
| 5 | Several "obviously right" fixes rested on premises nobody measured: touch bounds were already 48 dp; the 404-on-CDN-original rule was first judged on too few photos (107 of 850 originals in one gallery answer 404 while the photo shows); the status-bar fix assumed "only the viewers are dark". | Reading code instead of data, or checking one example. | CLAUDE.md already says check the premise. Add: **a premise about "all" or "none" needs a count over the whole set, and the count goes in the findings row.** |
| 6 | The emulator exit check (6-24) saw the white status bar on Home and the grid in the light theme, which 878 Robolectric tests missed, but recorded it as "a design question" and deferred it to P12. The phone then showed it was a real bug (#25). The androidTest smoke from 6-21 (`LiveSmokeTest`, J1/J3/J6) existed but never looked at both themes or every tab. | I saw the symptom and judged it by the premise "only viewers are dark" without checking every screen. Robolectric draws no real window, and the promise had no owner or step number. | A symptom seen on a device is a finding, not a deferral: it gets a row and a premise check in the same step. `ThemeSweepSmokeTest` now sweeps the main tabs in both themes (section 5). |
| 7 | One first full run failed: 3 offline tests red plus a JVM `hs_err` crash. Three isolated runs and a second full run on the same source passed. | Unexplained. My best guess is a build collision or the mutated file left from a red check; not proven. | Left as unexplained on purpose. If it recurs, keep the log and the `hs_err` file before re-running. |
| 8 | I wrote an unverified claim about finding #7 (release builds log nothing) and corrected it later. | I stated a conclusion from memory of one build. | Findings rows say "seen on X" or "from the code, not run". |
| 9 | A test depended on the real LAN (the SSDP scan). | No fake for discovery. | 6-24 fixed the logging bug (a quiet scan is not an error), and the test now uses a fake socket (section 5), so it can no longer pass vacuously. |
| 10 | The #26 breadcrumb bug was a `remember` keyed on the stack's size, caught by a 60-line Robolectric test, but only after you saw it on the phone. | No test drove two galleries at the same depth in a row. | The new `BreadcrumbScreenTest` does. Review rule: a `remember(x.size)` key is a smell, ask what else changes. |
| 11 | My adb navigation from the phone checks left SmugView and opened your Claude app's "Add context" sheet. | I pressed Back repeatedly without reading the screen between presses. | Dump the UI after every Back; stop at the launcher. |
| 12 | When you asked "is there a limit to the zoom?" I explained the 10x cap and recommended leaving it. You said a cap while there is zoom left defeats the point. | I treated an accidental limit as a design choice. The viewer's purpose is "as much as the pixels allow". | When asked "is there a limit", say first whether the limit serves the feature's purpose; if not, it is a bug and I propose the fix. |
| 13 | The test suite got slow: 4 minutes, 70% of it in one helper call. | `settle()` called `compose.waitForIdle()` three times, about 300 ms each, 534 times in `GoneBookmarkScenarioTest` alone. Nobody looked at per-class times as the suite grew from 92 to 899. | Section 3. |

Smaller lessons worth keeping: a screen test at 360 dp wide ran out of memory (the w360dp OOM); a gate that blocks the
main looper must be released from the test thread (the blocked-main-looper lesson); Compose test effects run on a
different thread than the test body; a compile error leaves stale test XML that looks like a pass; in the 6-18
mutation checks, mutate the root cause, not a guard that a second guard also catches.

## 3. Test-suite speed (done today)

- Measured: full suite 249 s wall, 944 s of CPU. One class, `GoneBookmarkScenarioTest`, took 179 s alone (72% of the wall
  time, since a class is the unit Gradle hands to a worker). Run alone it still took 184 s, so it was not contention.
- Found by timing, not guessing: the waits themselves were under 1 s per test; `settle()` was 162 s of the 184 s, all in
  `compose.waitForIdle()`. Disabling the clock's auto-advance changed nothing. Dropping `waitForIdle` from `settle()`
  (it already idles the looper and steps the clock) made it 19 s.
- Result: about 134 s wall, 438 s CPU, 899 tests, green twice. Committed with a findings row.
- Still slow: `OfflineGalleryTest` (43 s), `SearchWindowTest` (23 s), `LegacyRepairTest` (23 s), `OfflineReaderTest` (22 s),
  `AppModuleToastTest` (16 s for two tests). The first three are Room-heavy Robolectric classes; the toast one looks like it
  waits in real time for a retry. Next candidates, not done.
- Not tried: more parallel forks. `maxParallelForks` is half the logical cores (8 on this machine). More forks need a
  bigger test heap (Robolectric is memory-hungry, see the w360dp OOM), so measure before changing.
- Rule going forward: when the suite passes 5 minutes, or a class passes 60 s, look at the per-class XML times before
  adding anything else.

## 4. Phone versus emulator

You asked whether the phone checks were necessary, whether they behaved differently on the emulator, whether emulator
settings can reach parity, and how to cut phone time to what benefits most.

**Setup difference that matters.** The emulator is Android 14 (sdk 34, image `google_apis`, the only image installed) at
1080x1920. The phone is a Pixel running Android 17 (sdk 37) at 1080x2424. Same public SmugMug data; Family needs the
password typed (and by our rule nobody but you types it). I did not look for an sdk 37 image in the SDK Manager; that is
the first thing to check.

**Per check** (P1 to P12, design section 8.3). "Phone needed" means a good emulator setup could not replace it.

| Check | Phone needed? | Did it differ from the emulator? | Parity by config? |
|---|---|---|---|
| P1 baseline `report.txt` from the Play build | Yes, once | Not run on the emulator: the Play build's real data exists only on the phone, and the install path wipes it | No. Cheap and read-only; the only reason it is phone-only is that the wipe destroys the evidence. |
| P2 install path | Yes (your decision, real data) | n/a | n/a |
| P3 release smoke (R8) | No | Passed on the phone. The release APK installs on the emulator the same way | Full parity. Under-checked: the AGENTS rule says "a device", any device counts. |
| P4 `report.txt` after first sync | Mostly no | Public-listing counts are identical; Family-derived counts (lit dots, `FolderTreeSync complete`) depend on a typed password | High, except the Family part. |
| P5 Family password | Not the phone, but you | Same code path on any device | Full, if you type it once on the emulator. |
| P6 pinch zoom | Partly | The emulator can pinch (Ctrl-drag, or UiAutomator `pinchOpen`), but its GPU texture limit is the host's, not the Pixel's. The 8192 px decode cap I added today is exactly about that limit | Gesture: yes. GPU limit: no. |
| P7/P8 airplane mode, offline files | No | Passed on the phone. `adb shell cmd connectivity airplane-mode` works on the emulator. One state stayed unconfirmed everywhere: a never-opened gallery offline (unit tests only) | Full. |
| P9 cast to real devices | Yes | The emulator sits behind NAT and cannot see LAN discovery (SSDP/mDNS) | None. You verified it by hand. |
| P10 share to a real app | Partly | Link and EXIF strip are testable off the phone (`exiftool` on a pulled file). The real chooser and real target apps are not | Partial. |
| P11 download in Google Photos | Partly | `MediaStore` visibility can be queried on the emulator; Google Photos is not on the image | Partial (the Google Photos part is low value). |
| P12 light and dark system theme | No | The emulator had the light theme on and showed the same white bar on Home and the grid (6-24). I deferred it instead of treating it as a bug (#25) | Full. This was a judgement error, not device-only. |
| Breadcrumb (#26) | No | Reproducible anywhere; now has a test | Full. |

**Verdict.** Of 12 checks, 2 are strictly device-only (P9, and P1 for the data it alone could read), 3 are partly
device-only (P6 GPU limit, P10 chooser, P11 Google Photos), and the other 7 could have run on the emulator. Both bugs the
phone found (#25, #26) were catchable earlier: #25 by treating what the emulator showed in 6-24 as a bug, #26 by a
test. What the phone really bought us: confirmation on Android 17, real touch, a real GPU, a real LAN, and your account.

**What config can add on the emulator** (what I would do, not yet tried): create an sdk 37 AVD at 1080x2424 if the
image exists; script airplane mode and a metered Wi-Fi (`cmd netpolicy`, not verified here); fill the data partition with
`fallocate` to test the full-disk path (safer there than on your phone); write an androidTest smoke that opens
Home, Collections, a gallery and a viewer in light and dark and pinches once with UiAutomator; use `exiftool` on a
pulled share/download file.

**Shrunken phone checklist** (about 15 to 20 minutes, all with you holding the phone):
1. Cast: find, connect, run a slideshow, stop during connect (P9).
2. Pinch the Montego Bay panorama and one portrait photo; confirm zoom reaches native detail (P6, GPU limit).
3. Share a photo to a real app and download one; see both land (P10, P11).
4. One release smoke on Android 17: launch, Home loads, type the Family password once, toggle airplane mode once.

Everything else runs on the emulator beforehand, scripted, and the run's output goes in findings.

## 5. What was applied (2026-10-02, same day, at the owner's request)

1. **Emulator profile and script: partly.** `scripts/emulator-checks.sh` has `avd`, `smoke`, `offline`, `disk` and `shots`
   (it refuses any serial that is not `emulator-*`). `smoke` and `offline` were run on the Android 14 emulator and work.
   **The Android 17 (API 37) emulator does not work on this machine:** the image installed (`android-37.0;google_apis;x86_64`)
   and the AVD `SmugView37` (1080x2424) was created, but the system server crashed in a loop
   (`SurfaceControl.nativeCreate`) with the default and the `swiftshader_indirect` GPU modes, and the device kept going offline with
   `-gpu host`; I stopped there. So Android 17 behaviour stays a phone-only check. `disk` and `shots` were written but not run.
2. **androidTest sweep: done.** `ThemeSweepSmokeTest` checks the status bar colour, its icons and a dark left edge on Search,
   Tags and Collections in both system themes. Red check: with the status-bar colour set to white, 2 of its tests failed
   (`expected:<-16316663> but was:<-1>`); green with the real code (5 tests with the three `LiveSmokeTest` ones, on emulator-5554).
3. **CLAUDE.md rules: done** (premise counts, symptom is a finding, both themes, phone-only list, agent time box, per-class
   test times, `-PlocalBuild`).
4. **Version suffix: done.** `-PlocalBuild` adds `-local` to the version name, so `report.txt` shows `0.8.1-local (27)` for a
   device-test build. Not run against a release build yet.
5. **SSDP test off the real network: done.** `DefaultCastManager.ssdpSocketFactory` is a test seam; `SsdpScanLoggingTest` now
   uses a fake socket and asserts both searches were sent through it. Red check: with the seam bypassed the new assertion fails
   (`SsdpScanLoggingTest.kt:30`).
Full unit suite after these changes: 899 tests, 4 skipped, 0 failures.

## 6. Open items (not fixed)

- A sharp 1:1 zoom on very wide panoramas needs region decoding; today deep zoom past 8192 px upscales.
- The Save-to-collection dialog shows a blank "Selected Item:" for an untitled photo and clips the "New Collection
  Name..." hint.
- About 10 `BuildConfig.DEBUG`-gated `Log.e` sites remain (finding #7).
- Not confirmed on a device: the never-opened-gallery offline state, the metered Wi-Fi wording, full-disk resume, a real
  404-original download and kept-gallery fall-back (#24).
- v0.8.1 is on internal only. Promotion to production and a Play Console look at the release are yours to call.
