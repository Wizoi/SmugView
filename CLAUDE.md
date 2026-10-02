# SmugView — working rules

Short on purpose. Every rule here has cost time on this project. The longer reasoning, environment
notes and retros live in [.agents/AGENTS.md](.agents/AGENTS.md); open problems live in
[docs/findings.md](docs/findings.md), and the whole-app review is in
[docs/review/2026-09-29-whole-app-review.md](docs/review/2026-09-29-whole-app-review.md). Read both
before starting on a bug.

## Running things

- Gradle needs the Android Studio JBR. From Git Bash:
  `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew.bat testDebugUnitTest`
- **Don't pipe Gradle into `tail`/`grep` and read success from the output.** The pipe reports
  `tail`'s exit code. Redirect to a file and check `$?`, or grep for `BUILD SUCCESSFUL`.
- adb is at `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`. Use `export MSYS_NO_PATHCONV=1`
  for `adb shell` commands with `/data/` or `/sdcard/` paths, but not in the same command as the
  JBR export.
- Release builds are not debuggable (`run-as` fails) and currently log nothing (findings #7). To
  read a device's state, check the live API first, then a debug build on an emulator.
- Live API, read-only: `curl "https://api.smugmug.com/api/v2/...?APIKey=$KEY"` with the key from
  `local.properties` (`smugmug.api.key`). Never echo the key.

## Before changing behaviour

- **Check the premise against real data before writing a fix.** Run a curl against the live API
  and look at what the payload actually contains. In v0.7.6, four "obviously right" fixes shipped
  broken (findings #2, #3, #5, #6). Two minutes of curl would have caught #2 and #5.
- **Name where each side gets its data.** The dot reads `cached_nodes`; change detection reads
  `cached_albums`, fed by an *anonymous* `user!albums`. They disagreed, and that disagreement
  *was* the bug (findings #1, #4). AlbumKey ≠ NodeID (findings #5).
- **The second fix for one symptom is not a fix.** Retro v6 and v0.7.6 both patched the "new"
  dot. Before a third, answer: which single place should own "is something new here?"
- **Every fix gets a test that fails on the old code first.** Run it before the fix and say that
  it failed, and how. Don't use `git stash` for this; comment the fix out, or run the test in a
  `git worktree` at `HEAD`.
- **A test's data must look like the real thing.** Mirror the real topology (password folder →
  sub-folder → gallery, depth ≥ 2), real ID shapes (AlbumKey ≠ NodeID), and dates relative to
  now (a hardcoded date aged out of the 30-day window, findings #9). Fakes must behave like Room:
  `insertNodes` is REPLACE, not append (findings #8).
- **Design for how it fails before building.** That means an offline request with nothing cached,
  a locked gallery, a 429, a background sync racing a screen, and process death. Also decide
  what the user sees in each intermediate state. The v0.7.6 offline change didn't consider the
  uncached case (findings #6).
- **Ask before a one-way or meaning-changing choice.** Examples: a Room migration, changing what
  "viewed" or "new" means, a new default the user never chose. Get a plan signed off before
  editing, even for a bug fix that "looks small". AGENTS.md already required this and was not
  loaded (findings #13).

- **A premise about "all" or "none" needs a count over the whole set**, recorded in the findings row
  (retro v7: "only viewers are dark", "originals 404" on too few photos).
- **A symptom seen on a device is a finding in the same step**, with a row and a premise check. It is
  never a deferral (#25 was seen on the emulator and parked).
- **Look at every screen in both themes**, not only the screen the change touches.
- **Phone-only means** real LAN (cast), real touch/GPU, the real chooser/Google Photos and the owner's
  account. Everything else runs on the emulator first (`scripts/emulator-checks.sh`).
- **Every implementing agent gets a wall-clock box** (45 min) and commits or reports by it. The lead runs
  the full suite itself before any commit; an agent's "green" is a claim.
- **Check per-class test times** when the suite passes 5 minutes or a class passes 60 s
  (`settle()` once cost 162 of 184 s in one class).
- **Phone and Play builds**: build a device-test APK with `-PlocalBuild` so it reports `-local` in
  `report.txt` and cannot be mistaken for the Play build of the same versionCode.

## Things that are true here

- `cached_nodes` is filled lazily, on browse. `cached_albums` is synced eagerly, but only with
  what the anonymous listing can see. Anything under a password folder is invisible to it.
- Password folders are visible to `user!albums` only with the **session cookie** from
  `node/{id}!unlock`. The `Password=` query parameter does nothing there, and the cookie jar is
  in memory, so it is empty at every launch (findings #16).
- `user!albums` takes `Order`, not `SortMethod`/`SortDirection`, and even `Order` doesn't
  sort by any date it returns. Never stop a sync early on the assumption that it's sorted
  (findings #14).
- An album has three dates: album `LastUpdated`, `ImagesLastUpdated`, and node `DateModified`
  (≈ `ImagesLastUpdated`). They differ by days. Pick one per comparison (findings #15).
- A folder's `DateModified` is not a "something new inside" signal. It doesn't bump when its
  galleries change (findings #17), yet it does bump on site-wide events (findings #2).
- `RetryingCallFactory` retries 429 and 5xx, but not OkHttp's synthetic 504 from `only-if-cached`
  (`isSyntheticCacheMiss`). Coil gets its own cache-less client (R-37), so photos don't evict API
  responses.
- Never follow `Pages.NextPage`: it drops `_expand`/`_verbosity`. Page by `start` through `Pager`.
  Caps: albums 100, children 200, images 500, search 100.
- `OPTIONS` on an endpoint lists its accepted params. `ApiContractTest` checks every request
  against that list, so a param the server would ignore fails the build (R-27).
- **`Uris.ParentNode` is the node's *own* `!parent` link** (`/node/{SELF}!parent`), not its
  parent's ID. Never parse a parent out of it (review R-01). `user!albums` has no `ParentNode` at
  all; an album's parent comes from `Uris.Folder` or `UrlPath` (R-05).
- No GET honours a `Password=` parameter. Access comes only from the `!unlock` session cookie
  (R-23). Password-gallery CDN image URLs work with no session at all (R-53).
- A Gradle exit code of 0 has been seen on a failed build (a concurrent-build collision). Also
  check for `BUILD SUCCESSFUL` (R-69).
- **Saved photos are `offline_files` rows plus `filesDir/offline/{imageKey}.orig.{ext}`, owned by
  `OfflineStore`.** Nothing else writes there, and `collection_photos.localFilePath/isDownloaded`
  are dead columns. A file is wanted while a collection photo, an Image bookmark or a kept
  gallery's item refers to it (a query, not a counter). Screens read through `OfflineReader`, and
  only a DONE row's file is ever opened.
- **A video's `ArchivedUri` is a JPEG still, not the video.** A CDN original's ETag is its MD5
  and its path hash is not checked. **A 404 on an original does not mean the photo is gone**:
  107 of 850 originals in one gallery answer 404 while the photo displays (findings #24, `SMUGMUG.md`).
  Downloads and kept galleries fall back to the largest rendition (`Renditions`); a photo is gone
  only when its own `image/{key}-0` record is 404 too.
- **"Offline" for a screen means `SmugMugErrorMapper.isOffline`** (IOException or the synthetic
  504). A 429 or 5xx is SmugMug answering and must not be shown as "you're offline" or
  replaced by saved photos.
- **A locked folder's `!children` answers 404 anonymously (not 401), and a missing gallery is also
  404** (L2, L3). A 404 alone never means "gone": it is locked when the thing sits under a locked
  root. Never delete a bookmark or a saved file because of one.
- **SmugMug renditions (X3, L) keep the camera EXIF** (serial numbers, owner name; L7). Strip before
  sharing (`JpegStrip`); a shared *link* is the SmugMug web page (`WebUri`, L6).
- **Kept galleries follow one global network rule (`OfflineSettings`)**; `offline_files.wifiOnly` and
  `offline_galleries.wifiOnly` are dead columns.

## Before committing or shipping

- Run the full unit suite: `./gradlew.bat testDebugUnitTest`.
- Record the finding's row and the commit in `docs/findings.md`.
- Publishing to Play, `git push`, and destructive git operations each need explicit
  confirmation in the moment. A plan approval doesn't cover them (see AGENTS.md "Silent Mode
  EXCLUSIONS").
- Google Play requires targetSdk within 1 year of the latest Android (API 36 since
  2026-08-30). A rejected upload can still burn its versionCode.
